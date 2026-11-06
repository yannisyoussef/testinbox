# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [62.0]; concurrency [10, 100]; workspaces [10000] with 31 cohort shares; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws10000-c10-r62 | chosen-ws10000-c100-r62 |
|---|---|---|
| preLock | 1.23 / 0.90 / 2.49 / 7.15 | 1.15 / 0.88 / 2.63 / 4.65 |
| lockWait | 47.52 / 31.52 / 128.42 / 197.79 | 31.77 / 10.71 / 138.26 / 222.43 |
| hold | 13.11 / 12.00 / 21.06 / 35.62 | 11.33 / 10.11 / 19.46 / 29.69 |
| toSnapshot | 0.25 / 0.21 / 0.44 / 0.89 | 0.17 / 0.14 / 0.30 / 0.57 |
| snapshot | 8.44 / 7.44 / 13.43 / 24.30 | 6.98 / 6.23 / 11.26 / 22.97 |
| decide | 0.61 / 0.51 / 1.07 / 1.65 | 0.46 / 0.40 / 0.81 / 1.47 |
| insert | 3.75 / 3.17 / 7.10 / 12.58 | 3.68 / 3.09 / 7.59 / 11.67 |
| toCommit | 0.05 / 0.04 / 0.10 / 0.16 | 0.03 / 0.02 / 0.07 / 0.13 |
| commit | 0.01 / 0.01 / 0.01 / 0.03 | 0.00 / 0.00 / 0.00 / 0.01 |
| afterCommit | 0.04 / 0.03 / 0.08 / 0.14 | 0.02 / 0.02 / 0.03 / 0.06 |
| snapshot exec (server, mean) | 5.38 (max 46.14) | 4.12 (max 16.64) |
| snapshot plans in the phase × mean plan | 26 × 12.79 | 58 × 13.22 |
| T1 tx p50 / p99 | 46.10 / 221.76 | 23.20 / 249.71 |
| **T1 p50 / p95 / p99** (completion − due) | 48.37 / 348.63 / 458.89 | 25.39 / 165.88 / 435.16 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). Every pool connection was warmed past prepareThreshold before the phase, so a plan inside it is an invalidation (autovacuum/analyze), not a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws10000-c10-r62 | 62.00 | 61.89 | 3720 / 3720 | 121.44 | 0 | 0 | 0 |
| chosen-ws10000-c100-r62 | 62.00 | 61.96 | 3720 / 3720 | 58.03 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws10000-c10-r62 | 0.813 | 307.42 | 343.04 |
| chosen-ws10000-c100-r62 | 0.702 | 161.80 | 191.48 |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws10000-c10-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 167, relallvisible 159, reltuples 1680
storage_reservation: relpages 867, relallvisible 845, reltuples 1050
generic_plans = 7, custom_plans = 0
Append  (cost=2413.97..2956.15 rows=21 width=498) (actual time=5.197..6.072 rows=12 loops=1)
  Buffers: shared hit=1766
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.013 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.009..0.011 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.002..0.004 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.000..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..314.52 rows=2 width=40) (actual time=0.015..0.021 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.014..0.015 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=313.48..313.50 rows=1 width=64) (actual time=0.003..0.004 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=312.47..312.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..262.31 rows=10031 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=263.56..263.57 rows=1 width=64) (actual time=0.922..0.923 rows=1 loops=1)
          Buffers: shared hit=229
          ->  Seq Scan on storage_delta  (cost=0.00..252.04 rows=2304 width=16) (actual time=0.299..0.719 rows=2530 loops=1)
                Buffers: shared hit=229
  CTE g_reserved
    ->  Aggregate  (cost=1197.36..1197.37 rows=1 width=40) (actual time=2.714..2.715 rows=1 loops=1)
          Buffers: shared hit=1176
          ->  Seq Scan on storage_reservation  (cost=0.00..1190.24 rows=1424 width=12) (actual time=0.005..2.458 rows=1000 loops=1)
                Buffers: shared hit=1176
  CTE fp_newest
    ->  Limit  (cost=1.02..1.02 rows=1 width=112) (actual time=0.058..0.060 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.02..1.02 rows=1 width=112) (actual time=0.057..0.057 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.01 rows=1 width=112) (actual time=0.005..0.006 rows=1 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..1.47 rows=32 width=33) (actual time=0.008..0.015 rows=31 loops=1)
          Buffers: shared hit=1
          ->  Seq Scan on participant_workspace  (cost=0.00..0.00 rows=1 width=33) (actual time=0.002..0.003 rows=0 loops=1)
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.31 rows=31 width=33) (actual time=0.004..0.008 rows=31 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=12.34..12.35 rows=1 width=128) (actual time=0.329..0.331 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.060..0.061 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.44..11.29 rows=102 width=32) (actual time=0.169..0.289 rows=372 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=7
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..9.57 rows=102 width=32) (actual time=0.074..0.143 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=6
                ->  Hash  (cost=1.04..1.04 rows=32 width=16) (actual time=0.061..0.061 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=1
                      ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.041..0.045 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=1
                            ->  CTE Scan on cp_shares  (cost=0.00..0.64 rows=32 width=16) (actual time=0.015..0.029 rows=31 loops=1)
                                  Buffers: shared hit=1
  CTE cp_unattributed
    ->  Aggregate  (cost=10.05..10.06 rows=1 width=64) (actual time=0.095..0.098 rows=1 loops=1)
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Hash Right Anti Join  (cost=0.79..10.00 rows=1 width=16) (actual time=0.086..0.088 rows=0 loops=1)
                Hash Cond: (d_4.id = c_1.debt_id)
                ->  Seq Scan on storage_deletion_debt d_4  (cost=0.00..8.05 rows=305 width=8) (never executed)
                ->  Hash  (cost=0.78..0.78 rows=1 width=24) (actual time=0.034..0.035 rows=0 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                      ->  Hash Right Anti Join  (cost=0.01..0.78 rows=1 width=24) (actual time=0.034..0.035 rows=0 loops=1)
                            Hash Cond: (s_1.workspace_id = c_1.workspace_id)
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.64 rows=32 width=16) (never executed)
                            ->  Hash  (cost=0.00..0.00 rows=1 width=40) (actual time=0.002..0.003 rows=0 loops=1)
                                  Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                  ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..0.00 rows=1 width=40) (actual time=0.002..0.002 rows=0 loops=1)
  InitPlan 14 (returns $13)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.017..0.021 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $14)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.924..0.924 rows=1 loops=1)
          Buffers: shared hit=229
  InitPlan 16 (returns $15)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=2.716..2.717 rows=1 loops=1)
          Buffers: shared hit=1176
  InitPlan 17 (returns $16)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $17)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $18)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $19)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 21 (returns $20)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 22 (returns $21)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 23 (returns $22)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $23)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 25 (returns $24)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 26 (returns $25)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.331..0.331 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 27 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 28 (returns $27)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.007..0.007 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $28)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.008..0.008 rows=1 loops=1)
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
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..0.00 rows=1 width=0) (actual time=0.001..0.001 rows=0 loops=1)
  InitPlan 37 (returns $36)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..0.00 rows=1 width=0) (actual time=0.004..0.004 rows=0 loops=1)
  InitPlan 38 (returns $37)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..0.00 rows=1 width=32) (actual time=0.005..0.005 rows=0 loops=1)
          Filter: approved
  InitPlan 39 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.001 rows=0 loops=1)
          Filter: approved
  InitPlan 40 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..0.00 rows=1 width=8) (actual time=0.001..0.001 rows=0 loops=1)
  InitPlan 41 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..0.00 rows=1 width=8) (actual time=0.001..0.001 rows=0 loops=1)
  InitPlan 42 (returns $41)
    ->  Aggregate  (cost=0.75..0.76 rows=1 width=8) (actual time=0.019..0.020 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.00..0.74 rows=1 width=4) (actual time=0.016..0.017 rows=1 loops=1)
                Buffers: shared hit=1
                ->  Nested Loop Anti Join  (cost=0.00..7406.95 rows=9999 width=4) (actual time=0.015..0.015 rows=1 loops=1)
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
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.096..0.097 rows=1 loops=1)
  InitPlan 48 (returns $49)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 49 (returns $50)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $54)
    ->  Aggregate  (cost=607.60..607.61 rows=1 width=32) (actual time=0.952..0.957 rows=1 loops=1)
          Buffers: shared hit=220
          ->  Hash Left Join  (cost=366.91..607.12 rows=32 width=217) (actual time=0.565..0.701 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=220
                ->  Hash Left Join  (cost=205.64..445.75 rows=32 width=177) (actual time=0.357..0.485 rows=31 loops=1)
                      Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=158
                      ->  Nested Loop Left Join  (cost=205.59..445.61 rows=32 width=113) (actual time=0.313..0.432 rows=31 loops=1)
                            Buffers: shared hit=158
                            ->  Hash Right Join  (cost=205.31..207.93 rows=32 width=97) (actual time=0.266..0.276 rows=31 loops=1)
                                  Hash Cond: (storage_delta_1.workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=65
                                  ->  HashAggregate  (cost=204.27..205.47 rows=80 width=80) (actual time=0.213..0.214 rows=2 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=65
                                        ->  Nested Loop  (cost=1.00..203.67 rows=80 width=32) (actual time=0.166..0.201 rows=20 loops=1)
                                              Buffers: shared hit=65
                                              ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.013..0.019 rows=31 loops=1)
                                                    Group Key: cp_shares_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.64 rows=32 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                                              ->  Index Only Scan using ix_storage_delta_workspace on storage_delta storage_delta_1  (cost=0.28..6.31 rows=2 width=32) (actual time=0.005..0.005 rows=1 loops=31)
                                                    Index Cond: (workspace_id = cp_shares_1.workspace_id)
                                                    Heap Fetches: 20
                                                    Buffers: shared hit=65
                                  ->  Hash  (cost=0.64..0.64 rows=32 width=33) (actual time=0.024..0.024 rows=31 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.64 rows=32 width=33) (actual time=0.001..0.006 rows=31 loops=1)
                            ->  Index Scan using workspace_storage_account_pkey on workspace_storage_account a_2  (cost=0.29..7.43 rows=1 width=32) (actual time=0.004..0.004 rows=1 loops=31)
                                  Index Cond: (workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=93
                      ->  Hash  (cost=0.03..0.03 rows=1 width=80) (actual time=0.005..0.006 rows=0 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                            ->  Subquery Scan on c_2  (cost=0.01..0.03 rows=1 width=80) (actual time=0.004..0.005 rows=0 loops=1)
                                  ->  HashAggregate  (cost=0.01..0.02 rows=1 width=80) (actual time=0.003..0.004 rows=0 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..0.00 rows=1 width=32) (actual time=0.002..0.002 rows=0 loops=1)
                ->  Hash  (cost=160.84..160.84 rows=35 width=56) (actual time=0.168..0.170 rows=0 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                      Buffers: shared hit=62
                      ->  Subquery Scan on r_3  (cost=159.70..160.84 rows=35 width=56) (actual time=0.168..0.169 rows=0 loops=1)
                            Buffers: shared hit=62
                            ->  GroupAggregate  (cost=159.70..160.49 rows=35 width=56) (actual time=0.167..0.168 rows=0 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Buffers: shared hit=62
                                  ->  Sort  (cost=159.70..159.79 rows=35 width=28) (actual time=0.166..0.167 rows=0 loops=1)
                                        Sort Key: storage_reservation_1.workspace_id
                                        Sort Method: quicksort  Memory: 25kB
                                        Buffers: shared hit=62
                                        ->  Nested Loop  (cost=1.00..158.80 rows=35 width=28) (actual time=0.140..0.140 rows=0 loops=1)
                                              Buffers: shared hit=62
                                              ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.019..0.024 rows=31 loops=1)
                                                    Group Key: cp_shares_2.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.64 rows=32 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                                              ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..4.92 rows=1 width=28) (actual time=0.003..0.003 rows=0 loops=31)
                                                    Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                                    Heap Fetches: 0
                                                    Buffers: shared hit=62
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=5.197..5.198 rows=1 loops=1)
        Buffers: shared hit=1638
  ->  Hash Left Join  (cost=131.53..216.09 rows=10 width=498) (actual time=0.436..0.449 rows=1 loops=1)
        Hash Cond: (ws.id = d.id)
        Buffers: shared hit=8
        ->  Hash Left Join  (cost=58.12..142.53 rows=10 width=169) (actual time=0.331..0.343 rows=1 loops=1)
              Hash Cond: (ws.id = e.workspace_id)
              Buffers: shared hit=6
              ->  Hash Left Join  (cost=57.05..141.42 rows=10 width=168) (actual time=0.264..0.273 rows=1 loops=1)
                    Hash Cond: (ws.id = p.workspace_id)
                    Buffers: shared hit=6
                    ->  Hash Left Join  (cost=57.04..141.38 rows=10 width=152) (actual time=0.227..0.235 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=6
                          ->  Hash Left Join  (cost=56.67..140.98 rows=10 width=88) (actual time=0.187..0.192 rows=1 loops=1)
                                Hash Cond: (ws.id = r.id)
                                Buffers: shared hit=6
                                ->  Nested Loop Left Join  (cost=0.61..84.88 rows=10 width=48) (actual time=0.092..0.096 rows=1 loops=1)
                                      Buffers: shared hit=4
                                      ->  Hash Right Join  (cost=0.33..1.85 rows=10 width=32) (actual time=0.059..0.062 rows=1 loops=1)
                                            Hash Cond: (l.workspace_id = ws.id)
                                            Buffers: shared hit=1
                                            ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.31 rows=31 width=32) (actual time=0.004..0.006 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.028..0.028 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.014..0.014 rows=1 loops=1)
                                      ->  Index Scan using workspace_storage_account_pkey on workspace_storage_account a  (cost=0.29..8.30 rows=1 width=32) (actual time=0.031..0.031 rows=1 loops=1)
                                            Index Cond: (workspace_id = ws.id)
                                            Buffers: shared hit=3
                                ->  Hash  (cost=55.92..55.92 rows=11 width=56) (actual time=0.070..0.072 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=2
                                      ->  Subquery Scan on r  (cost=55.57..55.92 rows=11 width=56) (actual time=0.070..0.071 rows=0 loops=1)
                                            Buffers: shared hit=2
                                            ->  GroupAggregate  (cost=55.57..55.81 rows=11 width=56) (actual time=0.069..0.070 rows=0 loops=1)
                                                  Group Key: r_1.workspace_id
                                                  Buffers: shared hit=2
                                                  ->  Sort  (cost=55.57..55.59 rows=11 width=28) (actual time=0.067..0.068 rows=0 loops=1)
                                                        Sort Key: r_1.workspace_id
                                                        Sort Method: quicksort  Memory: 25kB
                                                        Buffers: shared hit=2
                                                        ->  Nested Loop  (cost=0.50..55.38 rows=11 width=28) (actual time=0.035..0.036 rows=0 loops=1)
                                                              Buffers: shared hit=2
                                                              ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.009 rows=1 loops=1)
                                                                    Group Key: ws_1.id
                                                                    Batches: 1  Memory Usage: 24kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.004..0.005 rows=1 loops=1)
                                                              ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..5.50 rows=1 width=28) (actual time=0.023..0.023 rows=0 loops=1)
                                                                    Index Cond: (workspace_id = ws_1.id)
                                                                    Heap Fetches: 0
                                                                    Buffers: shared hit=2
                          ->  Hash  (cost=0.36..0.36 rows=1 width=80) (actual time=0.006..0.008 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                ->  Subquery Scan on c  (cost=0.33..0.36 rows=1 width=80) (actual time=0.005..0.007 rows=0 loops=1)
                                      ->  HashAggregate  (cost=0.33..0.35 rows=1 width=80) (actual time=0.004..0.006 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  Nested Loop Semi Join  (cost=0.00..0.33 rows=1 width=32) (actual time=0.002..0.004 rows=0 loops=1)
                                                  Join Filter: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..0.00 rows=1 width=32) (actual time=0.002..0.002 rows=0 loops=1)
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (never executed)
                    ->  Hash  (cost=0.00..0.00 rows=1 width=32) (actual time=0.002..0.003 rows=0 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 8kB
                          ->  Seq Scan on participant_workspace p  (cost=0.00..0.00 rows=1 width=32) (actual time=0.002..0.002 rows=0 loops=1)
              ->  Hash  (cost=1.05..1.05 rows=1 width=17) (actual time=0.007..0.009 rows=0 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 8kB
                    ->  Subquery Scan on e  (cost=1.01..1.05 rows=1 width=17) (actual time=0.007..0.008 rows=0 loops=1)
                          ->  Nested Loop Left Join  (cost=1.01..1.04 rows=1 width=17) (actual time=0.006..0.007 rows=0 loops=1)
                                Join Filter: (h.id = a_1.account_id)
                                InitPlan 51 (returns $55)
                                  ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (never executed)
                                ->  Nested Loop Left Join  (cost=0.00..0.01 rows=1 width=48) (actual time=0.005..0.006 rows=0 loops=1)
                                      Join Filter: ((a_1.cohort_id = p_1.cohort_id) AND (a_1.place = p_1.place))
                                      ->  Seq Scan on participant_workspace p_1  (cost=0.00..0.00 rows=1 width=52) (actual time=0.005..0.005 rows=0 loops=1)
                                      ->  Seq Scan on beta_admission a_1  (cost=0.00..0.00 rows=1 width=68) (never executed)
                                ->  Seq Scan on human_account h  (cost=0.00..0.00 rows=1 width=56) (never executed)
        ->  Hash  (cost=73.10..73.10 rows=25 width=80) (actual time=0.065..0.066 rows=0 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 8kB
              Buffers: shared hit=2
              ->  Subquery Scan on d  (cost=72.22..73.10 rows=25 width=80) (actual time=0.064..0.065 rows=0 loops=1)
                    Buffers: shared hit=2
                    ->  GroupAggregate  (cost=72.22..72.85 rows=25 width=80) (actual time=0.064..0.065 rows=0 loops=1)
                          Group Key: d_1.workspace_id
                          Buffers: shared hit=2
                          ->  Sort  (cost=72.22..72.28 rows=25 width=32) (actual time=0.062..0.063 rows=0 loops=1)
                                Sort Key: d_1.workspace_id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=2
                                ->  Nested Loop  (cost=0.51..71.64 rows=25 width=32) (actual time=0.033..0.034 rows=0 loops=1)
                                      Buffers: shared hit=2
                                      ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                                            Group Key: ws_3.id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                      ->  Index Only Scan using ix_storage_delta_workspace on storage_delta d_1  (cost=0.28..7.11 rows=2 width=32) (actual time=0.025..0.025 rows=0 loops=1)
                                            Index Cond: (workspace_id = ws_3.id)
                                            Heap Fetches: 0
                                            Buffers: shared hit=2
  ->  Merge Left Join  (cost=156.74..325.98 rows=10 width=498) (actual time=0.283..0.390 rows=10 loops=1)
        Merge Cond: (ib.id = r_2.inbox_id)
        Buffers: shared hit=120
        ->  Nested Loop Left Join  (cost=73.20..241.81 rows=10 width=112) (actual time=0.189..0.289 rows=10 loops=1)
              Buffers: shared hit=100
              ->  Nested Loop Left Join  (cost=72.78..157.46 rows=10 width=96) (actual time=0.162..0.214 rows=10 loops=1)
                    Buffers: shared hit=60
                    ->  Merge Left Join  (cost=72.36..73.11 rows=10 width=80) (actual time=0.137..0.141 rows=10 loops=1)
                          Merge Cond: (ib.id = d_2.inbox_id)
                          Buffers: shared hit=20
                          ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.041..0.043 rows=10 loops=1)
                                Sort Key: ib.id
                                Sort Method: quicksort  Memory: 25kB
                                ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.011..0.014 rows=10 loops=1)
                          ->  GroupAggregate  (cost=71.99..72.39 rows=16 width=80) (actual time=0.093..0.094 rows=0 loops=1)
                                Group Key: d_2.inbox_id
                                Buffers: shared hit=20
                                ->  Sort  (cost=71.99..72.03 rows=16 width=32) (actual time=0.092..0.093 rows=0 loops=1)
                                      Sort Key: d_2.inbox_id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=20
                                      ->  Nested Loop  (cost=0.51..71.67 rows=16 width=32) (actual time=0.066..0.066 rows=0 loops=1)
                                            Buffers: shared hit=20
                                            ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.008 rows=10 loops=1)
                                                  Group Key: ib_1.id
                                                  Batches: 1  Memory Usage: 24kB
                                                  ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                            ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..7.11 rows=2 width=32) (actual time=0.005..0.005 rows=0 loops=10)
                                                  Index Cond: (inbox_id = ib_1.id)
                                                  Heap Fetches: 0
                                                  Buffers: shared hit=20
                    ->  Index Scan using inbox_pkey on inbox x  (cost=0.42..8.44 rows=1 width=32) (actual time=0.007..0.007 rows=1 loops=10)
                          Index Cond: (id = ib.id)
                          Buffers: shared hit=40
              ->  Index Scan using inbox_storage_pkey on inbox_storage s  (cost=0.42..8.44 rows=1 width=32) (actual time=0.007..0.007 rows=1 loops=10)
                    Index Cond: (inbox_id = ib.id)
                    Buffers: shared hit=40
        ->  GroupAggregate  (cost=83.54..83.79 rows=10 width=56) (actual time=0.087..0.088 rows=0 loops=1)
              Group Key: r_2.inbox_id
              Buffers: shared hit=20
              ->  Sort  (cost=83.54..83.57 rows=10 width=179) (actual time=0.085..0.086 rows=0 loops=1)
                    Sort Key: r_2.inbox_id
                    Sort Method: quicksort  Memory: 25kB
                    Buffers: shared hit=20
                    ->  Nested Loop  (cost=0.50..83.37 rows=10 width=179) (actual time=0.059..0.059 rows=0 loops=1)
                          Buffers: shared hit=20
                          ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.009 rows=10 loops=1)
                                Group Key: ib_2.id
                                Batches: 1  Memory Usage: 24kB
                                ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                          ->  Index Scan using ix_storage_reservation_inbox on storage_reservation r_2  (cost=0.28..8.29 rows=1 width=179) (actual time=0.005..0.005 rows=0 loops=10)
                                Index Cond: (inbox_id = ib_2.id)
                                Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.106 ms
Execution Time: 9.239 ms
```

## chosen-ws10000-c100-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 264, relallvisible 262, reltuples 1612
storage_reservation: relpages 1382, relallvisible 1377, reltuples 1001
generic_plans = 7, custom_plans = 0
Append  (cost=1468.00..1953.33 rows=21 width=498) (actual time=2.643..3.466 rows=12 loops=1)
  Buffers: shared hit=704
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.013 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.010..0.012 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.002..0.004 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.000..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..314.52 rows=2 width=40) (actual time=0.022..0.027 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.022..0.022 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=313.48..313.50 rows=1 width=64) (actual time=0.002..0.004 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=312.47..312.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..262.31 rows=10031 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=288.19..288.19 rows=1 width=64) (actual time=0.772..0.773 rows=1 loops=1)
          Buffers: shared hit=264
          ->  Seq Scan on storage_delta  (cost=0.00..280.12 rows=1612 width=16) (actual time=0.038..0.639 rows=1774 loops=1)
                Buffers: shared hit=264
  CTE g_reserved
    ->  Aggregate  (cost=320.30..320.31 rows=1 width=40) (actual time=0.430..0.430 rows=1 loops=1)
          Buffers: shared hit=72
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..315.29 rows=1001 width=12) (actual time=0.046..0.360 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=72
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.046..0.046 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.044..0.045 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.005..0.006 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..1.47 rows=32 width=33) (actual time=0.011..0.018 rows=31 loops=1)
          Buffers: shared hit=1
          ->  Seq Scan on participant_workspace  (cost=0.00..0.00 rows=1 width=33) (actual time=0.002..0.003 rows=0 loops=1)
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.31 rows=31 width=33) (actual time=0.008..0.011 rows=31 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=21.29..21.30 rows=1 width=128) (actual time=0.335..0.337 rows=1 loops=1)
          Buffers: shared hit=17
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.046..0.047 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.44..20.42 rows=84 width=32) (actual time=0.151..0.297 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=17
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..18.75 rows=84 width=32) (actual time=0.057..0.150 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=16
                ->  Hash  (cost=1.04..1.04 rows=32 width=16) (actual time=0.061..0.062 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=1
                      ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.036..0.041 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=1
                            ->  CTE Scan on cp_shares  (cost=0.00..0.64 rows=32 width=16) (actual time=0.012..0.026 rows=31 loops=1)
                                  Buffers: shared hit=1
  CTE cp_unattributed
    ->  Aggregate  (cost=7.80..7.81 rows=1 width=64) (actual time=0.116..0.118 rows=1 loops=1)
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.28..7.75 rows=1 width=16) (actual time=0.109..0.110 rows=0 loops=1)
                ->  Hash Right Anti Join  (cost=0.01..0.78 rows=1 width=24) (actual time=0.108..0.109 rows=0 loops=1)
                      Hash Cond: (s_1.workspace_id = c_1.workspace_id)
                      ->  CTE Scan on cp_shares s_1  (cost=0.00..0.64 rows=32 width=16) (never executed)
                      ->  Hash  (cost=0.00..0.00 rows=1 width=40) (actual time=0.007..0.008 rows=0 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                            ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..0.00 rows=1 width=40) (actual time=0.007..0.007 rows=0 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.27..4.29 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.024..0.027 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.773..0.774 rows=1 loops=1)
          Buffers: shared hit=264
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.431..0.431 rows=1 loops=1)
          Buffers: shared hit=72
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.337..0.337 rows=1 loops=1)
          Buffers: shared hit=17
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.006..0.007 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.009..0.009 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.004..0.004 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..0.00 rows=1 width=0) (actual time=0.001..0.001 rows=0 loops=1)
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..0.00 rows=1 width=0) (actual time=0.006..0.006 rows=0 loops=1)
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.001 rows=0 loops=1)
          Filter: approved
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.001 rows=0 loops=1)
          Filter: approved
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..0.00 rows=1 width=8) (actual time=0.001..0.001 rows=0 loops=1)
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..0.00 rows=1 width=8) (actual time=0.004..0.004 rows=0 loops=1)
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=0.75..0.76 rows=1 width=8) (actual time=0.016..0.018 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.00..0.74 rows=1 width=4) (actual time=0.014..0.014 rows=1 loops=1)
                Buffers: shared hit=1
                ->  Nested Loop Anti Join  (cost=0.00..7406.95 rows=9999 width=4) (actual time=0.013..0.014 rows=1 loops=1)
                      Join Filter: (s_2.workspace_id = w.id)
                      Rows Removed by Join Filter: 31
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..184.31 rows=10031 width=16) (actual time=0.004..0.005 rows=1 loops=1)
                            Buffers: shared hit=1
                      ->  CTE Scan on cp_shares s_2  (cost=0.00..0.64 rows=32 width=16) (actual time=0.000..0.003 rows=31 loops=1)
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
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.118..0.118 rows=1 loops=1)
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $55)
    ->  Aggregate  (cost=507.38..507.39 rows=1 width=32) (actual time=0.811..0.816 rows=1 loops=1)
          Buffers: shared hit=217
          ->  Hash Left Join  (cost=266.69..506.89 rows=32 width=217) (actual time=0.456..0.583 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=217
                ->  Hash Left Join  (cost=141.65..381.76 rows=32 width=177) (actual time=0.310..0.429 rows=31 loops=1)
                      Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=155
                      ->  Nested Loop Left Join  (cost=141.60..381.62 rows=32 width=113) (actual time=0.273..0.386 rows=31 loops=1)
                            Buffers: shared hit=155
                            ->  Hash Right Join  (cost=141.32..143.94 rows=32 width=97) (actual time=0.242..0.251 rows=31 loops=1)
                                  Hash Cond: (storage_delta_1.workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=62
                                  ->  HashAggregate  (cost=140.28..141.48 rows=80 width=80) (actual time=0.174..0.176 rows=0 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=62
                                        ->  Nested Loop  (cost=1.00..139.68 rows=80 width=32) (actual time=0.172..0.173 rows=0 loops=1)
                                              Buffers: shared hit=62
                                              ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.012..0.018 rows=31 loops=1)
                                                    Group Key: cp_shares_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.64 rows=32 width=16) (actual time=0.000..0.003 rows=31 loops=1)
                                              ->  Index Only Scan using ix_storage_delta_workspace on storage_delta storage_delta_1  (cost=0.28..4.31 rows=2 width=32) (actual time=0.005..0.005 rows=0 loops=31)
                                                    Index Cond: (workspace_id = cp_shares_1.workspace_id)
                                                    Heap Fetches: 0
                                                    Buffers: shared hit=62
                                  ->  Hash  (cost=0.64..0.64 rows=32 width=33) (actual time=0.032..0.033 rows=31 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.64 rows=32 width=33) (actual time=0.001..0.006 rows=31 loops=1)
                            ->  Index Scan using workspace_storage_account_pkey on workspace_storage_account a_2  (cost=0.29..7.43 rows=1 width=32) (actual time=0.004..0.004 rows=1 loops=31)
                                  Index Cond: (workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=93
                      ->  Hash  (cost=0.03..0.03 rows=1 width=80) (actual time=0.004..0.005 rows=0 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                            ->  Subquery Scan on c_2  (cost=0.01..0.03 rows=1 width=80) (actual time=0.003..0.004 rows=0 loops=1)
                                  ->  HashAggregate  (cost=0.01..0.02 rows=1 width=80) (actual time=0.003..0.003 rows=0 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..0.00 rows=1 width=32) (actual time=0.002..0.002 rows=0 loops=1)
                ->  Hash  (cost=124.62..124.62 rows=33 width=56) (actual time=0.115..0.116 rows=0 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                      Buffers: shared hit=62
                      ->  Subquery Scan on r_3  (cost=123.55..124.62 rows=33 width=56) (actual time=0.114..0.115 rows=0 loops=1)
                            Buffers: shared hit=62
                            ->  GroupAggregate  (cost=123.55..124.29 rows=33 width=56) (actual time=0.114..0.115 rows=0 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Buffers: shared hit=62
                                  ->  Sort  (cost=123.55..123.63 rows=33 width=28) (actual time=0.113..0.114 rows=0 loops=1)
                                        Sort Key: storage_reservation_1.workspace_id
                                        Sort Method: quicksort  Memory: 25kB
                                        Buffers: shared hit=62
                                        ->  Nested Loop  (cost=0.99..122.72 rows=33 width=28) (actual time=0.087..0.087 rows=0 loops=1)
                                              Buffers: shared hit=62
                                              ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.012..0.016 rows=31 loops=1)
                                                    Group Key: cp_shares_2.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.64 rows=32 width=16) (actual time=0.000..0.003 rows=31 loops=1)
                                              ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..3.79 rows=1 width=28) (actual time=0.002..0.002 rows=0 loops=31)
                                                    Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                                    Heap Fetches: 0
                                                    Buffers: shared hit=62
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=2.643..2.643 rows=1 loops=1)
        Buffers: shared hit=576
  ->  Hash Left Join  (cost=98.80..183.35 rows=10 width=498) (actual time=0.398..0.409 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=8
        ->  Hash Left Join  (cost=50.84..135.22 rows=10 width=193) (actual time=0.303..0.313 rows=1 loops=1)
              Hash Cond: (ws.id = e.workspace_id)
              Buffers: shared hit=6
              ->  Hash Left Join  (cost=49.77..134.11 rows=10 width=192) (actual time=0.242..0.249 rows=1 loops=1)
                    Hash Cond: (ws.id = p.workspace_id)
                    Buffers: shared hit=6
                    ->  Hash Left Join  (cost=49.76..134.07 rows=10 width=176) (actual time=0.213..0.219 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=6
                          ->  Nested Loop Left Join  (cost=49.39..133.66 rows=10 width=112) (actual time=0.180..0.184 rows=1 loops=1)
                                Buffers: shared hit=6
                                ->  Hash Right Join  (cost=49.11..50.63 rows=10 width=96) (actual time=0.157..0.162 rows=1 loops=1)
                                      Hash Cond: (l.workspace_id = ws.id)
                                      Buffers: shared hit=3
                                      ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.31 rows=31 width=32) (actual time=0.004..0.006 rows=31 loops=1)
                                            Buffers: shared hit=1
                                      ->  Hash  (cost=48.98..48.98 rows=10 width=80) (actual time=0.125..0.126 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=2
                                            ->  Hash Right Join  (cost=48.16..48.98 rows=10 width=80) (actual time=0.109..0.113 rows=1 loops=1)
                                                  Hash Cond: (d.workspace_id = ws.id)
                                                  Buffers: shared hit=2
                                                  ->  HashAggregate  (cost=47.84..48.21 rows=25 width=80) (actual time=0.043..0.044 rows=0 loops=1)
                                                        Group Key: d.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=2
                                                        ->  Nested Loop  (cost=0.50..47.65 rows=25 width=32) (actual time=0.041..0.042 rows=0 loops=1)
                                                              Buffers: shared hit=2
                                                              ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.004..0.005 rows=1 loops=1)
                                                                    Group Key: ws_1.id
                                                                    Batches: 1  Memory Usage: 24kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
                                                              ->  Index Only Scan using ix_storage_delta_workspace on storage_delta d  (cost=0.28..4.71 rows=2 width=32) (actual time=0.033..0.033 rows=0 loops=1)
                                                                    Index Cond: (workspace_id = ws_1.id)
                                                                    Heap Fetches: 0
                                                                    Buffers: shared hit=2
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.027..0.028 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.013..0.014 rows=1 loops=1)
                                ->  Index Scan using workspace_storage_account_pkey on workspace_storage_account a  (cost=0.29..8.30 rows=1 width=32) (actual time=0.019..0.019 rows=1 loops=1)
                                      Index Cond: (workspace_id = ws.id)
                                      Buffers: shared hit=3
                          ->  Hash  (cost=0.36..0.36 rows=1 width=80) (actual time=0.005..0.005 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                ->  Subquery Scan on c  (cost=0.33..0.36 rows=1 width=80) (actual time=0.004..0.005 rows=0 loops=1)
                                      ->  HashAggregate  (cost=0.33..0.35 rows=1 width=80) (actual time=0.003..0.004 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  Nested Loop Semi Join  (cost=0.00..0.33 rows=1 width=32) (actual time=0.002..0.002 rows=0 loops=1)
                                                  Join Filter: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.001 rows=0 loops=1)
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (never executed)
                    ->  Hash  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.002 rows=0 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 8kB
                          ->  Seq Scan on participant_workspace p  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.001 rows=0 loops=1)
              ->  Hash  (cost=1.05..1.05 rows=1 width=17) (actual time=0.003..0.005 rows=0 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 8kB
                    ->  Subquery Scan on e  (cost=1.01..1.05 rows=1 width=17) (actual time=0.003..0.004 rows=0 loops=1)
                          ->  Nested Loop Left Join  (cost=1.01..1.04 rows=1 width=17) (actual time=0.002..0.003 rows=0 loops=1)
                                Join Filter: (h.id = a_1.account_id)
                                InitPlan 51 (returns $56)
                                  ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (never executed)
                                ->  Nested Loop Left Join  (cost=0.00..0.01 rows=1 width=48) (actual time=0.001..0.002 rows=0 loops=1)
                                      Join Filter: ((a_1.cohort_id = p_1.cohort_id) AND (a_1.place = p_1.place))
                                      ->  Seq Scan on participant_workspace p_1  (cost=0.00..0.00 rows=1 width=52) (actual time=0.001..0.001 rows=0 loops=1)
                                      ->  Seq Scan on beta_admission a_1  (cost=0.00..0.00 rows=1 width=68) (never executed)
                                ->  Seq Scan on human_account h  (cost=0.00..0.00 rows=1 width=56) (never executed)
        ->  Hash  (cost=47.84..47.84 rows=10 width=56) (actual time=0.056..0.057 rows=0 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 8kB
              Buffers: shared hit=2
              ->  Subquery Scan on r  (cost=47.52..47.84 rows=10 width=56) (actual time=0.055..0.056 rows=0 loops=1)
                    Buffers: shared hit=2
                    ->  GroupAggregate  (cost=47.52..47.74 rows=10 width=56) (actual time=0.054..0.055 rows=0 loops=1)
                          Group Key: r_1.workspace_id
                          Buffers: shared hit=2
                          ->  Sort  (cost=47.52..47.54 rows=10 width=28) (actual time=0.053..0.053 rows=0 loops=1)
                                Sort Key: r_1.workspace_id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=2
                                ->  Nested Loop  (cost=0.50..47.35 rows=10 width=28) (actual time=0.023..0.024 rows=0 loops=1)
                                      Buffers: shared hit=2
                                      ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.003..0.004 rows=1 loops=1)
                                            Group Key: ws_3.id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
                                      ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..4.69 rows=1 width=28) (actual time=0.018..0.018 rows=0 loops=1)
                                            Index Cond: (workspace_id = ws_3.id)
                                            Heap Fetches: 0
                                            Buffers: shared hit=2
  ->  Merge Left Join  (cost=132.66..301.87 rows=10 width=498) (actual time=0.264..0.380 rows=10 loops=1)
        Merge Cond: (ib.id = r_2.inbox_id)
        Buffers: shared hit=120
        ->  Nested Loop Left Join  (cost=49.14..217.72 rows=10 width=112) (actual time=0.182..0.290 rows=10 loops=1)
              Buffers: shared hit=100
              ->  Nested Loop Left Join  (cost=48.73..133.37 rows=10 width=96) (actual time=0.156..0.213 rows=10 loops=1)
                    Buffers: shared hit=60
                    ->  Merge Left Join  (cost=48.31..49.02 rows=10 width=80) (actual time=0.125..0.128 rows=10 loops=1)
                          Merge Cond: (ib.id = d_1.inbox_id)
                          Buffers: shared hit=20
                          ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.044..0.045 rows=10 loops=1)
                                Sort Key: ib.id
                                Sort Method: quicksort  Memory: 25kB
                                ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.011..0.015 rows=10 loops=1)
                          ->  GroupAggregate  (cost=47.94..48.32 rows=15 width=80) (actual time=0.078..0.079 rows=0 loops=1)
                                Group Key: d_1.inbox_id
                                Buffers: shared hit=20
                                ->  Sort  (cost=47.94..47.98 rows=15 width=32) (actual time=0.077..0.078 rows=0 loops=1)
                                      Sort Key: d_1.inbox_id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=20
                                      ->  Nested Loop  (cost=0.50..47.65 rows=15 width=32) (actual time=0.058..0.058 rows=0 loops=1)
                                            Buffers: shared hit=20
                                            ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.009 rows=10 loops=1)
                                                  Group Key: ib_1.id
                                                  Batches: 1  Memory Usage: 24kB
                                                  ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=10 loops=1)
                                            ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..4.71 rows=2 width=32) (actual time=0.005..0.005 rows=0 loops=10)
                                                  Index Cond: (inbox_id = ib_1.id)
                                                  Heap Fetches: 0
                                                  Buffers: shared hit=20
                    ->  Index Scan using inbox_pkey on inbox x  (cost=0.42..8.44 rows=1 width=32) (actual time=0.008..0.008 rows=1 loops=10)
                          Index Cond: (id = ib.id)
                          Buffers: shared hit=40
              ->  Index Scan using inbox_storage_pkey on inbox_storage s  (cost=0.42..8.44 rows=1 width=32) (actual time=0.007..0.007 rows=1 loops=10)
                    Index Cond: (inbox_id = ib.id)
                    Buffers: shared hit=40
        ->  GroupAggregate  (cost=83.52..83.77 rows=10 width=56) (actual time=0.077..0.078 rows=0 loops=1)
              Group Key: r_2.inbox_id
              Buffers: shared hit=20
              ->  Sort  (cost=83.52..83.54 rows=10 width=172) (actual time=0.076..0.077 rows=0 loops=1)
                    Sort Key: r_2.inbox_id
                    Sort Method: quicksort  Memory: 25kB
                    Buffers: shared hit=20
                    ->  Nested Loop  (cost=0.50..83.35 rows=10 width=172) (actual time=0.055..0.056 rows=0 loops=1)
                          Buffers: shared hit=20
                          ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                                Group Key: ib_2.id
                                Batches: 1  Memory Usage: 24kB
                                ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=10 loops=1)
                          ->  Index Scan using ix_storage_reservation_inbox on storage_reservation r_2  (cost=0.28..8.29 rows=1 width=172) (actual time=0.004..0.005 rows=0 loops=10)
                                Index Cond: (inbox_id = ib_2.id)
                                Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.133 ms
Execution Time: 6.046 ms
```
