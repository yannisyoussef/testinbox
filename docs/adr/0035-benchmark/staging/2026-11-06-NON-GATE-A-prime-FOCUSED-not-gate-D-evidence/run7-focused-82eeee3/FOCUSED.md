# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [62.0]; concurrency [10, 100]; workspaces [10000] with 31 cohort shares; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws10000-c10-r62 | chosen-ws10000-c100-r62 |
|---|---|---|
| preLock | 0.94 / 0.79 / 1.75 / 3.37 | 0.99 / 0.78 / 2.10 / 4.13 |
| lockWait | 44.34 / 26.91 / 121.64 / 155.19 | 91.87 / 64.08 / 232.06 / 283.37 |
| hold | 13.39 / 12.61 / 19.08 / 27.86 | 14.18 / 13.02 / 22.66 / 33.00 |
| toSnapshot | 0.21 / 0.19 / 0.35 / 0.53 | 0.20 / 0.15 / 0.29 / 0.55 |
| snapshot | 9.31 / 8.75 / 12.99 / 21.46 | 10.04 / 9.17 / 15.39 / 26.77 |
| decide | 0.53 / 0.47 / 0.95 / 1.35 | 0.44 / 0.38 / 0.71 / 1.16 |
| insert | 2.10 / 1.85 / 3.49 / 6.56 | 2.26 / 1.94 / 4.08 / 7.49 |
| toCommit | 0.04 / 0.03 / 0.09 / 0.13 | 0.02 / 0.02 / 0.06 / 0.09 |
| commit | 1.21 / 1.05 / 2.02 / 3.60 | 1.23 / 1.06 / 2.25 / 3.74 |
| afterCommit | 0.05 / 0.04 / 0.11 / 0.19 | 0.02 / 0.02 / 0.04 / 0.07 |
| snapshot exec (server, mean) | 6.58 (max 26.00) | 7.03 (max 38.38) |
| snapshot plans in the phase × mean plan | 26 × 12.44 | 66 × 12.58 |
| T1 tx p50 / p99 | 41.31 / 174.77 | 79.71 / 304.37 |
| **T1 p50 / p95 / p99** (completion − due) | 43.06 / 234.05 / 331.10 | 81.42 / 406.98 / 667.78 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). Every pool connection was warmed past prepareThreshold before the phase, so a plan inside it is an invalidation (autovacuum/analyze), not a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws10000-c10-r62 | 62.00 | 61.85 | 3720 / 3720 | 162.21 | 0 | 0 | 0 |
| chosen-ws10000-c100-r62 | 62.00 | 61.99 | 3720 / 3720 | 22.49 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws10000-c10-r62 | 0.830 | 348.86 | 376.72 |
| chosen-ws10000-c100-r62 | 0.879 | 526.76 | 559.76 |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws10000-c10-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 146, relallvisible 137, reltuples 2396
storage_reservation: relpages 742, relallvisible 727, reltuples 1050
generic_plans = 7, custom_plans = 0
Append  (cost=2781.47..3390.79 rows=21 width=498) (actual time=6.915..8.076 rows=12 loops=1)
  Buffers: shared hit=1922
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.017 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.009 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.014..0.016 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.006 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..314.52 rows=2 width=40) (actual time=0.034..0.043 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.034..0.035 rows=1 loops=1)
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
    ->  Aggregate  (cost=328.99..329.00 rows=1 width=64) (actual time=1.091..1.092 rows=1 loops=1)
          Buffers: shared hit=264
          ->  Seq Scan on storage_delta  (cost=0.00..307.32 rows=4332 width=16) (actual time=0.342..0.858 rows=2646 loops=1)
                Buffers: shared hit=264
  CTE g_reserved
    ->  Aggregate  (cost=1328.10..1328.11 rows=1 width=40) (actual time=3.412..3.414 rows=1 loops=1)
          Buffers: shared hit=1296
          ->  Seq Scan on storage_reservation  (cost=0.00..1314.34 rows=1834 width=163) (actual time=0.007..3.148 rows=1000 loops=1)
                Buffers: shared hit=1296
  CTE fp_newest
    ->  Limit  (cost=1.02..1.02 rows=1 width=112) (actual time=0.120..0.121 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.02..1.02 rows=1 width=112) (actual time=0.117..0.118 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.01 rows=1 width=112) (actual time=0.013..0.014 rows=1 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..1.47 rows=32 width=33) (actual time=0.013..0.022 rows=31 loops=1)
          Buffers: shared hit=1
          ->  Seq Scan on participant_workspace  (cost=0.00..0.00 rows=1 width=33) (actual time=0.004..0.005 rows=0 loops=1)
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.31 rows=31 width=33) (actual time=0.007..0.011 rows=31 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=11.39..11.40 rows=1 width=128) (actual time=0.521..0.526 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.129..0.129 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.44..10.50 rows=86 width=32) (actual time=0.326..0.473 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=7
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..8.82 rows=86 width=32) (actual time=0.180..0.268 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=6
                ->  Hash  (cost=1.04..1.04 rows=32 width=16) (actual time=0.098..0.099 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=1
                      ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.061..0.067 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=1
                            ->  CTE Scan on cp_shares  (cost=0.00..0.64 rows=32 width=16) (actual time=0.016..0.042 rows=31 loops=1)
                                  Buffers: shared hit=1
  CTE cp_unattributed
    ->  Aggregate  (cost=9.36..9.38 rows=1 width=64) (actual time=0.122..0.128 rows=1 loops=1)
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Hash Right Anti Join  (cost=0.79..9.31 rows=1 width=16) (actual time=0.112..0.116 rows=0 loops=1)
                Hash Cond: (d_3.id = c_1.debt_id)
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..7.55 rows=255 width=8) (never executed)
                ->  Hash  (cost=0.78..0.78 rows=1 width=24) (actual time=0.048..0.050 rows=0 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                      ->  Hash Right Anti Join  (cost=0.01..0.78 rows=1 width=24) (actual time=0.047..0.049 rows=0 loops=1)
                            Hash Cond: (s_1.workspace_id = c_1.workspace_id)
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.64 rows=32 width=16) (never executed)
                            ->  Hash  (cost=0.00..0.00 rows=1 width=40) (actual time=0.004..0.005 rows=0 loops=1)
                                  Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                  ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..0.00 rows=1 width=40) (actual time=0.004..0.004 rows=0 loops=1)
  InitPlan 14 (returns $13)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.039..0.044 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $14)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=1.093..1.094 rows=1 loops=1)
          Buffers: shared hit=264
  InitPlan 16 (returns $15)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=3.417..3.420 rows=1 loops=1)
          Buffers: shared hit=1296
  InitPlan 17 (returns $16)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 18 (returns $17)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 19 (returns $18)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $19)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $20)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 22 (returns $21)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 23 (returns $22)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $23)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 25 (returns $24)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $25)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.525..0.525 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 27 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $27)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.009..0.009 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $28)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.005..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $29)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $30)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 32 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 34 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 36 (returns $35)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..0.00 rows=1 width=0) (actual time=0.001..0.002 rows=0 loops=1)
  InitPlan 37 (returns $36)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..0.00 rows=1 width=0) (actual time=0.005..0.005 rows=0 loops=1)
  InitPlan 38 (returns $37)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.002 rows=0 loops=1)
          Filter: approved
  InitPlan 39 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..0.00 rows=1 width=32) (actual time=0.006..0.006 rows=0 loops=1)
          Filter: approved
  InitPlan 40 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..0.00 rows=1 width=8) (actual time=0.001..0.002 rows=0 loops=1)
  InitPlan 41 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..0.00 rows=1 width=8) (actual time=0.002..0.002 rows=0 loops=1)
  InitPlan 42 (returns $41)
    ->  Aggregate  (cost=0.75..0.76 rows=1 width=8) (actual time=0.028..0.031 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.00..0.74 rows=1 width=4) (actual time=0.024..0.026 rows=1 loops=1)
                Buffers: shared hit=1
                ->  Nested Loop Anti Join  (cost=0.00..7406.95 rows=9999 width=4) (actual time=0.023..0.024 rows=1 loops=1)
                      Join Filter: (s_2.workspace_id = w.id)
                      Rows Removed by Join Filter: 31
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..184.31 rows=10031 width=16) (actual time=0.008..0.008 rows=1 loops=1)
                            Buffers: shared hit=1
                      ->  CTE Scan on cp_shares s_2  (cost=0.00..0.64 rows=32 width=16) (actual time=0.004..0.008 rows=31 loops=1)
  InitPlan 46 (returns $47)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..1.21 rows=1 width=1) (actual time=0.002..0.006 rows=0 loops=1)
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
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.124..0.124 rows=1 loops=1)
  InitPlan 48 (returns $49)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $50)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $54)
    ->  Aggregate  (cost=780.59..780.60 rows=1 width=32) (actual time=1.412..1.424 rows=1 loops=1)
          Buffers: shared hit=221
          ->  Hash Left Join  (cost=539.35..780.11 rows=32 width=217) (actual time=0.713..0.900 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_2.workspace_id)
                Buffers: shared hit=221
                ->  Hash Left Join  (cost=289.99..530.65 rows=32 width=177) (actual time=0.455..0.631 rows=31 loops=1)
                      Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=159
                      ->  Nested Loop Left Join  (cost=289.95..530.51 rows=32 width=113) (actual time=0.407..0.576 rows=31 loops=1)
                            Buffers: shared hit=159
                            ->  Hash Right Join  (cost=289.66..292.83 rows=32 width=97) (actual time=0.373..0.387 rows=31 loops=1)
                                  Hash Cond: (storage_delta_1.workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=66
                                  ->  HashAggregate  (cost=288.62..290.11 rows=99 width=80) (actual time=0.286..0.290 rows=3 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=66
                                        ->  Nested Loop  (cost=1.00..287.88 rows=99 width=32) (actual time=0.220..0.273 rows=22 loops=1)
                                              Buffers: shared hit=66
                                              ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.018..0.025 rows=31 loops=1)
                                                    Group Key: cp_shares_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.64 rows=32 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                                              ->  Index Only Scan using ix_storage_delta_workspace on storage_delta storage_delta_1  (cost=0.28..8.93 rows=3 width=32) (actual time=0.007..0.007 rows=1 loops=31)
                                                    Index Cond: (workspace_id = cp_shares_1.workspace_id)
                                                    Heap Fetches: 22
                                                    Buffers: shared hit=66
                                  ->  Hash  (cost=0.64..0.64 rows=32 width=33) (actual time=0.048..0.048 rows=31 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.64 rows=32 width=33) (actual time=0.006..0.011 rows=31 loops=1)
                            ->  Index Scan using workspace_storage_account_pkey on workspace_storage_account a_2  (cost=0.29..7.43 rows=1 width=32) (actual time=0.006..0.006 rows=1 loops=31)
                                  Index Cond: (workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=93
                      ->  Hash  (cost=0.03..0.03 rows=1 width=80) (actual time=0.006..0.008 rows=0 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                            ->  Subquery Scan on c_2  (cost=0.01..0.03 rows=1 width=80) (actual time=0.006..0.007 rows=0 loops=1)
                                  ->  HashAggregate  (cost=0.01..0.02 rows=1 width=80) (actual time=0.005..0.005 rows=0 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..0.00 rows=1 width=32) (actual time=0.003..0.003 rows=0 loops=1)
                ->  Hash  (cost=248.92..248.92 rows=35 width=56) (actual time=0.219..0.224 rows=0 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                      Buffers: shared hit=62
                      ->  Subquery Scan on r_2  (cost=247.70..248.92 rows=35 width=56) (actual time=0.219..0.222 rows=0 loops=1)
                            Buffers: shared hit=62
                            ->  GroupAggregate  (cost=247.70..248.57 rows=35 width=56) (actual time=0.218..0.220 rows=0 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Buffers: shared hit=62
                                  ->  Sort  (cost=247.70..247.79 rows=35 width=179) (actual time=0.216..0.218 rows=0 loops=1)
                                        Sort Key: storage_reservation_1.workspace_id
                                        Sort Method: quicksort  Memory: 25kB
                                        Buffers: shared hit=62
                                        ->  Nested Loop  (cost=1.00..246.80 rows=35 width=179) (actual time=0.176..0.177 rows=0 loops=1)
                                              Buffers: shared hit=62
                                              ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.023..0.029 rows=31 loops=1)
                                                    Group Key: cp_shares_2.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.64 rows=32 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                                              ->  Index Scan using ix_storage_reservation_workspace on storage_reservation storage_reservation_1  (cost=0.28..7.67 rows=1 width=179) (actual time=0.004..0.004 rows=0 loops=31)
                                                    Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                                    Buffers: shared hit=62
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=6.913..6.957 rows=1 loops=1)
        Buffers: shared hit=1794
  ->  Hash Left Join  (cost=186.34..271.14 rows=10 width=498) (actual time=0.412..0.427 rows=1 loops=1)
        Hash Cond: (ws.id = c.id)
        Buffers: shared hit=8
        ->  Merge Left Join  (cost=185.97..270.60 rows=10 width=169) (actual time=0.357..0.371 rows=1 loops=1)
              Merge Cond: (ws.id = e.workspace_id)
              Buffers: shared hit=8
              ->  Merge Left Join  (cost=184.91..269.50 rows=10 width=168) (actual time=0.308..0.316 rows=1 loops=1)
                    Merge Cond: (ws.id = r.workspace_id)
                    Buffers: shared hit=8
                    ->  Nested Loop Left Join  (cost=101.34..185.40 rows=10 width=128) (actual time=0.242..0.247 rows=1 loops=1)
                          Buffers: shared hit=6
                          ->  Merge Left Join  (cost=101.06..102.37 rows=10 width=112) (actual time=0.203..0.208 rows=1 loops=1)
                                Merge Cond: (ws.id = d.workspace_id)
                                Buffers: shared hit=3
                                ->  Sort  (cost=2.06..2.08 rows=10 width=48) (actual time=0.122..0.124 rows=1 loops=1)
                                      Sort Key: ws.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=1
                                      ->  Hash Left Join  (cost=0.34..1.89 rows=10 width=48) (actual time=0.096..0.100 rows=1 loops=1)
                                            Hash Cond: (ws.id = p.workspace_id)
                                            Buffers: shared hit=1
                                            ->  Hash Right Join  (cost=0.33..1.85 rows=10 width=32) (actual time=0.067..0.070 rows=1 loops=1)
                                                  Hash Cond: (l.workspace_id = ws.id)
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.31 rows=31 width=32) (actual time=0.007..0.010 rows=31 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.031..0.031 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.017..0.018 rows=1 loops=1)
                                            ->  Hash  (cost=0.00..0.00 rows=1 width=32) (actual time=0.005..0.005 rows=0 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                                  ->  Seq Scan on participant_workspace p  (cost=0.00..0.00 rows=1 width=32) (actual time=0.004..0.004 rows=0 loops=1)
                                ->  GroupAggregate  (cost=99.00..99.78 rows=31 width=80) (actual time=0.077..0.079 rows=0 loops=1)
                                      Group Key: d.workspace_id
                                      Buffers: shared hit=2
                                      ->  Sort  (cost=99.00..99.08 rows=31 width=32) (actual time=0.076..0.077 rows=0 loops=1)
                                            Sort Key: d.workspace_id
                                            Sort Method: quicksort  Memory: 25kB
                                            Buffers: shared hit=2
                                            ->  Nested Loop  (cost=0.51..98.23 rows=31 width=32) (actual time=0.044..0.045 rows=0 loops=1)
                                                  Buffers: shared hit=2
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.008 rows=1 loops=1)
                                                        Group Key: ws_1.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                                  ->  Index Only Scan using ix_storage_delta_workspace on storage_delta d  (cost=0.28..9.76 rows=3 width=32) (actual time=0.033..0.033 rows=0 loops=1)
                                                        Index Cond: (workspace_id = ws_1.id)
                                                        Heap Fetches: 0
                                                        Buffers: shared hit=2
                          ->  Index Scan using workspace_storage_account_pkey on workspace_storage_account a  (cost=0.29..8.30 rows=1 width=32) (actual time=0.034..0.034 rows=1 loops=1)
                                Index Cond: (workspace_id = ws.id)
                                Buffers: shared hit=3
                    ->  GroupAggregate  (cost=83.57..83.84 rows=11 width=56) (actual time=0.063..0.065 rows=0 loops=1)
                          Group Key: r.workspace_id
                          Buffers: shared hit=2
                          ->  Sort  (cost=83.57..83.59 rows=11 width=179) (actual time=0.061..0.063 rows=0 loops=1)
                                Sort Key: r.workspace_id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=2
                                ->  Nested Loop  (cost=0.50..83.37 rows=11 width=179) (actual time=0.029..0.030 rows=0 loops=1)
                                      Buffers: shared hit=2
                                      ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.008 rows=1 loops=1)
                                            Group Key: ws_2.id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                      ->  Index Scan using ix_storage_reservation_workspace on storage_reservation r  (cost=0.28..8.29 rows=1 width=179) (actual time=0.019..0.019 rows=0 loops=1)
                                            Index Cond: (workspace_id = ws_2.id)
                                            Buffers: shared hit=2
              ->  Sort  (cost=1.06..1.07 rows=1 width=17) (actual time=0.045..0.049 rows=0 loops=1)
                    Sort Key: e.workspace_id
                    Sort Method: quicksort  Memory: 25kB
                    ->  Subquery Scan on e  (cost=1.01..1.05 rows=1 width=17) (actual time=0.006..0.009 rows=0 loops=1)
                          ->  Nested Loop Left Join  (cost=1.01..1.04 rows=1 width=17) (actual time=0.004..0.007 rows=0 loops=1)
                                Join Filter: (h.id = a_1.account_id)
                                InitPlan 51 (returns $55)
                                  ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (never executed)
                                ->  Nested Loop Left Join  (cost=0.00..0.01 rows=1 width=48) (actual time=0.003..0.005 rows=0 loops=1)
                                      Join Filter: ((a_1.cohort_id = p_1.cohort_id) AND (a_1.place = p_1.place))
                                      ->  Seq Scan on participant_workspace p_1  (cost=0.00..0.00 rows=1 width=52) (actual time=0.002..0.003 rows=0 loops=1)
                                      ->  Seq Scan on beta_admission a_1  (cost=0.00..0.00 rows=1 width=68) (never executed)
                                ->  Seq Scan on human_account h  (cost=0.00..0.00 rows=1 width=56) (never executed)
        ->  Hash  (cost=0.36..0.36 rows=1 width=80) (actual time=0.007..0.009 rows=0 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 8kB
              ->  Subquery Scan on c  (cost=0.33..0.36 rows=1 width=80) (actual time=0.007..0.008 rows=0 loops=1)
                    ->  HashAggregate  (cost=0.33..0.35 rows=1 width=80) (actual time=0.006..0.007 rows=0 loops=1)
                          Group Key: workspace_deletion_charge.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          ->  Nested Loop Semi Join  (cost=0.00..0.33 rows=1 width=32) (actual time=0.003..0.004 rows=0 loops=1)
                                Join Filter: (workspace_deletion_charge.workspace_id = ws_3.id)
                                ->  Seq Scan on workspace_deletion_charge  (cost=0.00..0.00 rows=1 width=32) (actual time=0.002..0.002 rows=0 loops=1)
                                ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (never executed)
  ->  Merge Left Join  (cost=168.79..338.07 rows=10 width=498) (actual time=0.457..0.620 rows=10 loops=1)
        Merge Cond: (ib.id = r_1.inbox_id)
        Buffers: shared hit=120
        ->  Nested Loop Left Join  (cost=85.25..253.90 rows=10 width=112) (actual time=0.332..0.487 rows=10 loops=1)
              Buffers: shared hit=100
              ->  Nested Loop Left Join  (cost=84.83..169.55 rows=10 width=96) (actual time=0.281..0.362 rows=10 loops=1)
                    Buffers: shared hit=60
                    ->  Merge Left Join  (cost=84.41..85.20 rows=10 width=80) (actual time=0.208..0.213 rows=10 loops=1)
                          Merge Cond: (ib.id = d_1.inbox_id)
                          Buffers: shared hit=20
                          ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.073..0.074 rows=10 loops=1)
                                Sort Key: ib.id
                                Sort Method: quicksort  Memory: 25kB
                                ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.016..0.020 rows=10 loops=1)
                          ->  GroupAggregate  (cost=84.05..84.47 rows=17 width=80) (actual time=0.131..0.132 rows=0 loops=1)
                                Group Key: d_1.inbox_id
                                Buffers: shared hit=20
                                ->  Sort  (cost=84.05..84.09 rows=17 width=32) (actual time=0.129..0.130 rows=0 loops=1)
                                      Sort Key: d_1.inbox_id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=20
                                      ->  Nested Loop  (cost=0.51..83.70 rows=17 width=32) (actual time=0.107..0.107 rows=0 loops=1)
                                            Buffers: shared hit=20
                                            ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.013 rows=10 loops=1)
                                                  Group Key: ib_1.id
                                                  Batches: 1  Memory Usage: 24kB
                                                  ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                            ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..8.32 rows=2 width=32) (actual time=0.009..0.009 rows=0 loops=10)
                                                  Index Cond: (inbox_id = ib_1.id)
                                                  Heap Fetches: 0
                                                  Buffers: shared hit=20
                    ->  Index Scan using inbox_pkey on inbox x  (cost=0.42..8.44 rows=1 width=32) (actual time=0.011..0.011 rows=1 loops=10)
                          Index Cond: (id = ib.id)
                          Buffers: shared hit=40
              ->  Index Scan using inbox_storage_pkey on inbox_storage s  (cost=0.42..8.44 rows=1 width=32) (actual time=0.011..0.011 rows=1 loops=10)
                    Index Cond: (inbox_id = ib.id)
                    Buffers: shared hit=40
        ->  GroupAggregate  (cost=83.54..83.79 rows=10 width=56) (actual time=0.115..0.116 rows=0 loops=1)
              Group Key: r_1.inbox_id
              Buffers: shared hit=20
              ->  Sort  (cost=83.54..83.57 rows=10 width=179) (actual time=0.114..0.115 rows=0 loops=1)
                    Sort Key: r_1.inbox_id
                    Sort Method: quicksort  Memory: 25kB
                    Buffers: shared hit=20
                    ->  Nested Loop  (cost=0.50..83.37 rows=10 width=179) (actual time=0.086..0.086 rows=0 loops=1)
                          Buffers: shared hit=20
                          ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.013 rows=10 loops=1)
                                Group Key: ib_2.id
                                Batches: 1  Memory Usage: 24kB
                                ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                          ->  Index Scan using ix_storage_reservation_inbox on storage_reservation r_1  (cost=0.28..8.29 rows=1 width=179) (actual time=0.007..0.007 rows=0 loops=10)
                                Index Cond: (inbox_id = ib_2.id)
                                Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.144 ms
Execution Time: 11.387 ms
```

## chosen-ws10000-c100-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 237, relallvisible 178, reltuples 974
storage_reservation: relpages 1450, relallvisible 1435, reltuples 1050
generic_plans = 7, custom_plans = 0
Append  (cost=2764.57..3309.72 rows=21 width=498) (actual time=5.036..5.908 rows=12 loops=1)
  Buffers: shared hit=2055
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.013..0.014 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.007..0.008 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.009..0.010 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.002..0.004 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..314.52 rows=2 width=40) (actual time=0.016..0.023 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.016..0.017 rows=1 loops=1)
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
    ->  Aggregate  (cost=251.62..251.62 rows=1 width=64) (actual time=0.634..0.634 rows=1 loops=1)
          Buffers: shared hit=237
          ->  Seq Scan on storage_delta  (cost=0.00..246.74 rows=974 width=16) (actual time=0.013..0.526 rows=1500 loops=1)
                Buffers: shared hit=237
  CTE g_reserved
    ->  Aggregate  (cost=1468.38..1468.39 rows=1 width=40) (actual time=2.352..2.352 rows=1 loops=1)
          Buffers: shared hit=1450
          ->  Seq Scan on storage_reservation  (cost=0.00..1460.50 rows=1050 width=163) (actual time=0.007..2.106 rows=1000 loops=1)
                Buffers: shared hit=1450
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.085..0.086 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.083..0.083 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.006..0.007 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..1.47 rows=32 width=33) (actual time=0.013..0.020 rows=31 loops=1)
          Buffers: shared hit=1
          ->  Seq Scan on participant_workspace  (cost=0.00..0.00 rows=1 width=33) (actual time=0.004..0.005 rows=0 loops=1)
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.31 rows=31 width=33) (actual time=0.007..0.011 rows=31 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=20.90..20.91 rows=1 width=128) (actual time=0.402..0.405 rows=1 loops=1)
          Buffers: shared hit=17
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.087..0.087 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.44..20.10 rows=77 width=32) (actual time=0.224..0.362 rows=372 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=17
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..18.45 rows=77 width=32) (actual time=0.103..0.189 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=16
                ->  Hash  (cost=1.04..1.04 rows=32 width=16) (actual time=0.076..0.077 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=1
                      ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.051..0.056 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=1
                            ->  CTE Scan on cp_shares  (cost=0.00..0.64 rows=32 width=16) (actual time=0.015..0.038 rows=31 loops=1)
                                  Buffers: shared hit=1
  CTE cp_unattributed
    ->  Aggregate  (cost=7.80..7.81 rows=1 width=64) (actual time=0.046..0.049 rows=1 loops=1)
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.28..7.75 rows=1 width=16) (actual time=0.038..0.040 rows=0 loops=1)
                ->  Hash Right Anti Join  (cost=0.01..0.78 rows=1 width=24) (actual time=0.037..0.039 rows=0 loops=1)
                      Hash Cond: (s_1.workspace_id = c_1.workspace_id)
                      ->  CTE Scan on cp_shares s_1  (cost=0.00..0.64 rows=32 width=16) (never executed)
                      ->  Hash  (cost=0.00..0.00 rows=1 width=40) (actual time=0.003..0.004 rows=0 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                            ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..0.00 rows=1 width=40) (actual time=0.003..0.003 rows=0 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.27..4.29 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.021..0.025 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.636..0.636 rows=1 loops=1)
          Buffers: shared hit=237
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=2.356..2.357 rows=1 loops=1)
          Buffers: shared hit=1450
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.406..0.406 rows=1 loops=1)
          Buffers: shared hit=17
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.005..0.006 rows=1 loops=1)
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
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..0.00 rows=1 width=0) (actual time=0.004..0.004 rows=0 loops=1)
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..0.00 rows=1 width=0) (actual time=0.002..0.002 rows=0 loops=1)
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.001 rows=0 loops=1)
          Filter: approved
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.001 rows=0 loops=1)
          Filter: approved
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..0.00 rows=1 width=8) (actual time=0.004..0.004 rows=0 loops=1)
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..0.00 rows=1 width=8) (actual time=0.001..0.001 rows=0 loops=1)
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=0.75..0.76 rows=1 width=8) (actual time=0.018..0.020 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.00..0.74 rows=1 width=4) (actual time=0.014..0.015 rows=1 loops=1)
                Buffers: shared hit=1
                ->  Nested Loop Anti Join  (cost=0.00..7406.95 rows=9999 width=4) (actual time=0.014..0.014 rows=1 loops=1)
                      Join Filter: (s_2.workspace_id = w.id)
                      Rows Removed by Join Filter: 31
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..184.31 rows=10031 width=16) (actual time=0.005..0.005 rows=1 loops=1)
                            Buffers: shared hit=1
                      ->  CTE Scan on cp_shares s_2  (cost=0.00..0.64 rows=32 width=16) (actual time=0.000..0.003 rows=31 loops=1)
  InitPlan 46 (returns $48)
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
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.047..0.048 rows=1 loops=1)
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $55)
    ->  Aggregate  (cost=692.82..692.83 rows=1 width=32) (actual time=1.318..1.323 rows=1 loops=1)
          Buffers: shared hit=217
          ->  Merge Left Join  (cost=450.55..692.33 rows=32 width=217) (actual time=0.598..0.741 rows=31 loops=1)
                Merge Cond: (s_3.workspace_id = workspace_deletion_charge_1.workspace_id)
                Buffers: shared hit=217
                ->  Merge Left Join  (cost=450.54..692.19 rows=32 width=153) (actual time=0.568..0.704 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = storage_reservation_1.workspace_id)
                      Buffers: shared hit=217
                      ->  Nested Loop Left Join  (cost=198.70..438.64 rows=32 width=113) (actual time=0.388..0.517 rows=31 loops=1)
                            Buffers: shared hit=155
                            ->  Merge Left Join  (cost=198.42..200.96 rows=32 width=97) (actual time=0.226..0.237 rows=31 loops=1)
                                  Merge Cond: (s_3.workspace_id = storage_delta_1.workspace_id)
                                  Buffers: shared hit=62
                                  ->  Sort  (cost=1.44..1.52 rows=32 width=33) (actual time=0.054..0.057 rows=31 loops=1)
                                        Sort Key: s_3.workspace_id
                                        Sort Method: quicksort  Memory: 26kB
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.64 rows=32 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                  ->  GroupAggregate  (cost=196.98..198.35 rows=55 width=80) (actual time=0.169..0.170 rows=0 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Buffers: shared hit=62
                                        ->  Sort  (cost=196.98..197.11 rows=55 width=32) (actual time=0.168..0.169 rows=0 loops=1)
                                              Sort Key: storage_delta_1.workspace_id
                                              Sort Method: quicksort  Memory: 25kB
                                              Buffers: shared hit=62
                                              ->  Nested Loop  (cost=0.99..195.39 rows=55 width=32) (actual time=0.143..0.144 rows=0 loops=1)
                                                    Buffers: shared hit=62
                                                    ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.016..0.020 rows=31 loops=1)
                                                          Group Key: cp_shares_1.workspace_id
                                                          Batches: 1  Memory Usage: 24kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.64 rows=32 width=16) (actual time=0.001..0.003 rows=31 loops=1)
                                                    ->  Index Only Scan using ix_storage_delta_workspace on storage_delta storage_delta_1  (cost=0.28..6.05 rows=2 width=32) (actual time=0.004..0.004 rows=0 loops=31)
                                                          Index Cond: (workspace_id = cp_shares_1.workspace_id)
                                                          Heap Fetches: 0
                                                          Buffers: shared hit=62
                            ->  Index Scan using workspace_storage_account_pkey on workspace_storage_account a_2  (cost=0.29..7.43 rows=1 width=32) (actual time=0.008..0.008 rows=1 loops=31)
                                  Index Cond: (workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=93
                      ->  GroupAggregate  (cost=251.84..252.72 rows=35 width=56) (actual time=0.178..0.179 rows=0 loops=1)
                            Group Key: storage_reservation_1.workspace_id
                            Buffers: shared hit=62
                            ->  Sort  (cost=251.84..251.93 rows=35 width=179) (actual time=0.176..0.178 rows=0 loops=1)
                                  Sort Key: storage_reservation_1.workspace_id
                                  Sort Method: quicksort  Memory: 25kB
                                  Buffers: shared hit=62
                                  ->  Nested Loop  (cost=1.00..250.94 rows=35 width=179) (actual time=0.124..0.125 rows=0 loops=1)
                                        Buffers: shared hit=62
                                        ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.012..0.017 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.64 rows=32 width=16) (actual time=0.001..0.003 rows=31 loops=1)
                                        ->  Index Scan using ix_storage_reservation_workspace on storage_reservation storage_reservation_1  (cost=0.28..7.80 rows=1 width=179) (actual time=0.003..0.003 rows=0 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Buffers: shared hit=62
                ->  GroupAggregate  (cost=0.01..0.04 rows=1 width=80) (actual time=0.028..0.029 rows=0 loops=1)
                      Group Key: workspace_deletion_charge_1.workspace_id
                      ->  Sort  (cost=0.01..0.02 rows=1 width=32) (actual time=0.026..0.027 rows=0 loops=1)
                            Sort Key: workspace_deletion_charge_1.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..0.00 rows=1 width=32) (actual time=0.002..0.003 rows=0 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=5.035..5.077 rows=1 loops=1)
        Buffers: shared hit=1927
  ->  Hash Left Join  (cost=150.91..235.49 rows=10 width=498) (actual time=0.357..0.370 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=8
        ->  Hash Left Join  (cost=66.78..151.19 rows=10 width=193) (actual time=0.261..0.272 rows=1 loops=1)
              Hash Cond: (ws.id = e.workspace_id)
              Buffers: shared hit=6
              ->  Hash Left Join  (cost=65.71..150.08 rows=10 width=192) (actual time=0.233..0.241 rows=1 loops=1)
                    Hash Cond: (ws.id = p.workspace_id)
                    Buffers: shared hit=6
                    ->  Hash Left Join  (cost=65.70..150.04 rows=10 width=176) (actual time=0.203..0.210 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=6
                          ->  Hash Left Join  (cost=65.33..149.63 rows=10 width=112) (actual time=0.143..0.149 rows=1 loops=1)
                                Hash Cond: (ws.id = d.id)
                                Buffers: shared hit=6
                                ->  Nested Loop Left Join  (cost=0.61..84.88 rows=10 width=48) (actual time=0.070..0.074 rows=1 loops=1)
                                      Buffers: shared hit=4
                                      ->  Hash Right Join  (cost=0.33..1.85 rows=10 width=32) (actual time=0.051..0.054 rows=1 loops=1)
                                            Hash Cond: (l.workspace_id = ws.id)
                                            Buffers: shared hit=1
                                            ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.31 rows=31 width=32) (actual time=0.005..0.007 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.027..0.027 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.016..0.016 rows=1 loops=1)
                                      ->  Index Scan using workspace_storage_account_pkey on workspace_storage_account a  (cost=0.29..8.30 rows=1 width=32) (actual time=0.017..0.017 rows=1 loops=1)
                                            Index Cond: (workspace_id = ws.id)
                                            Buffers: shared hit=3
                                ->  Hash  (cost=64.51..64.51 rows=17 width=80) (actual time=0.058..0.059 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=2
                                      ->  Subquery Scan on d  (cost=63.91..64.51 rows=17 width=80) (actual time=0.058..0.059 rows=0 loops=1)
                                            Buffers: shared hit=2
                                            ->  GroupAggregate  (cost=63.91..64.34 rows=17 width=80) (actual time=0.057..0.058 rows=0 loops=1)
                                                  Group Key: d_1.workspace_id
                                                  Buffers: shared hit=2
                                                  ->  Sort  (cost=63.91..63.95 rows=17 width=32) (actual time=0.055..0.056 rows=0 loops=1)
                                                        Sort Key: d_1.workspace_id
                                                        Sort Method: quicksort  Memory: 25kB
                                                        Buffers: shared hit=2
                                                        ->  Nested Loop  (cost=0.50..63.56 rows=17 width=32) (actual time=0.031..0.031 rows=0 loops=1)
                                                              Buffers: shared hit=2
                                                              ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.004..0.004 rows=1 loops=1)
                                                                    Group Key: ws_1.id
                                                                    Batches: 1  Memory Usage: 24kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                              ->  Index Only Scan using ix_storage_delta_workspace on storage_delta d_1  (cost=0.28..6.30 rows=2 width=32) (actual time=0.025..0.025 rows=0 loops=1)
                                                                    Index Cond: (workspace_id = ws_1.id)
                                                                    Heap Fetches: 0
                                                                    Buffers: shared hit=2
                          ->  Hash  (cost=0.36..0.36 rows=1 width=80) (actual time=0.005..0.006 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                ->  Subquery Scan on c  (cost=0.33..0.36 rows=1 width=80) (actual time=0.004..0.005 rows=0 loops=1)
                                      ->  HashAggregate  (cost=0.33..0.35 rows=1 width=80) (actual time=0.003..0.004 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  Nested Loop Semi Join  (cost=0.00..0.33 rows=1 width=32) (actual time=0.002..0.003 rows=0 loops=1)
                                                  Join Filter: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..0.00 rows=1 width=32) (actual time=0.002..0.002 rows=0 loops=1)
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (never executed)
                    ->  Hash  (cost=0.00..0.00 rows=1 width=32) (actual time=0.002..0.002 rows=0 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 8kB
                          ->  Seq Scan on participant_workspace p  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.001 rows=0 loops=1)
              ->  Hash  (cost=1.05..1.05 rows=1 width=17) (actual time=0.003..0.006 rows=0 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 8kB
                    ->  Subquery Scan on e  (cost=1.01..1.05 rows=1 width=17) (actual time=0.003..0.005 rows=0 loops=1)
                          ->  Nested Loop Left Join  (cost=1.01..1.04 rows=1 width=17) (actual time=0.002..0.004 rows=0 loops=1)
                                Join Filter: (h.id = a_1.account_id)
                                InitPlan 51 (returns $56)
                                  ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (never executed)
                                ->  Nested Loop Left Join  (cost=0.00..0.01 rows=1 width=48) (actual time=0.002..0.003 rows=0 loops=1)
                                      Join Filter: ((a_1.cohort_id = p_1.cohort_id) AND (a_1.place = p_1.place))
                                      ->  Seq Scan on participant_workspace p_1  (cost=0.00..0.00 rows=1 width=52) (actual time=0.001..0.002 rows=0 loops=1)
                                      ->  Seq Scan on beta_admission a_1  (cost=0.00..0.00 rows=1 width=68) (never executed)
                                ->  Seq Scan on human_account h  (cost=0.00..0.00 rows=1 width=56) (never executed)
        ->  Hash  (cost=84.00..84.00 rows=11 width=56) (actual time=0.051..0.052 rows=0 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 8kB
              Buffers: shared hit=2
              ->  Subquery Scan on r  (cost=83.61..84.00 rows=11 width=56) (actual time=0.050..0.051 rows=0 loops=1)
                    Buffers: shared hit=2
                    ->  GroupAggregate  (cost=83.61..83.89 rows=11 width=56) (actual time=0.049..0.050 rows=0 loops=1)
                          Group Key: r_1.workspace_id
                          Buffers: shared hit=2
                          ->  Sort  (cost=83.61..83.64 rows=11 width=179) (actual time=0.048..0.049 rows=0 loops=1)
                                Sort Key: r_1.workspace_id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=2
                                ->  Nested Loop  (cost=0.50..83.42 rows=11 width=179) (actual time=0.024..0.025 rows=0 loops=1)
                                      Buffers: shared hit=2
                                      ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.004..0.004 rows=1 loops=1)
                                            Group Key: ws_3.id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                      ->  Index Scan using ix_storage_reservation_workspace on storage_reservation r_1  (cost=0.28..8.30 rows=1 width=179) (actual time=0.018..0.019 rows=0 loops=1)
                                            Index Cond: (workspace_id = ws_3.id)
                                            Buffers: shared hit=2
  ->  Merge Left Join  (cost=140.42..309.55 rows=10 width=498) (actual time=0.282..0.416 rows=10 loops=1)
        Merge Cond: (ib.id = r_2.inbox_id)
        Buffers: shared hit=120
        ->  Nested Loop Left Join  (cost=56.79..225.29 rows=10 width=112) (actual time=0.191..0.319 rows=10 loops=1)
              Buffers: shared hit=100
              ->  Nested Loop Left Join  (cost=56.37..140.94 rows=10 width=96) (actual time=0.164..0.240 rows=10 loops=1)
                    Buffers: shared hit=60
                    ->  Merge Left Join  (cost=55.96..56.59 rows=10 width=80) (actual time=0.127..0.131 rows=10 loops=1)
                          Merge Cond: (ib.id = d_2.inbox_id)
                          Buffers: shared hit=20
                          ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.040..0.041 rows=10 loops=1)
                                Sort Key: ib.id
                                Sort Method: quicksort  Memory: 25kB
                                ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.010..0.013 rows=10 loops=1)
                          ->  GroupAggregate  (cost=55.59..55.92 rows=13 width=80) (actual time=0.085..0.086 rows=0 loops=1)
                                Group Key: d_2.inbox_id
                                Buffers: shared hit=20
                                ->  Sort  (cost=55.59..55.62 rows=13 width=32) (actual time=0.084..0.085 rows=0 loops=1)
                                      Sort Key: d_2.inbox_id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=20
                                      ->  Nested Loop  (cost=0.50..55.35 rows=13 width=32) (actual time=0.064..0.065 rows=0 loops=1)
                                            Buffers: shared hit=20
                                            ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.008 rows=10 loops=1)
                                                  Group Key: ib_1.id
                                                  Batches: 1  Memory Usage: 24kB
                                                  ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                            ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..5.49 rows=1 width=32) (actual time=0.005..0.005 rows=0 loops=10)
                                                  Index Cond: (inbox_id = ib_1.id)
                                                  Heap Fetches: 0
                                                  Buffers: shared hit=20
                    ->  Index Scan using inbox_pkey on inbox x  (cost=0.42..8.44 rows=1 width=32) (actual time=0.010..0.010 rows=1 loops=10)
                          Index Cond: (id = ib.id)
                          Buffers: shared hit=40
              ->  Index Scan using inbox_storage_pkey on inbox_storage s  (cost=0.42..8.44 rows=1 width=32) (actual time=0.007..0.007 rows=1 loops=10)
                    Index Cond: (inbox_id = ib.id)
                    Buffers: shared hit=40
        ->  GroupAggregate  (cost=83.63..83.88 rows=10 width=56) (actual time=0.086..0.087 rows=0 loops=1)
              Group Key: r_2.inbox_id
              Buffers: shared hit=20
              ->  Sort  (cost=83.63..83.66 rows=10 width=179) (actual time=0.084..0.085 rows=0 loops=1)
                    Sort Key: r_2.inbox_id
                    Sort Method: quicksort  Memory: 25kB
                    Buffers: shared hit=20
                    ->  Nested Loop  (cost=0.50..83.47 rows=10 width=179) (actual time=0.063..0.064 rows=0 loops=1)
                          Buffers: shared hit=20
                          ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.008 rows=10 loops=1)
                                Group Key: ib_2.id
                                Batches: 1  Memory Usage: 24kB
                                ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                          ->  Index Scan using ix_storage_reservation_inbox on storage_reservation r_2  (cost=0.28..8.30 rows=1 width=179) (actual time=0.005..0.005 rows=0 loops=10)
                                Index Cond: (inbox_id = ib_2.id)
                                Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.123 ms
Execution Time: 8.620 ms
```
