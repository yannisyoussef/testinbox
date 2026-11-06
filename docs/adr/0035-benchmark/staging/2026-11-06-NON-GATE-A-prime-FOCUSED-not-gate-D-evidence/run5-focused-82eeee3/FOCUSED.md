# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [62.0]; concurrency [10, 100]; workspaces [10000] with 31 cohort shares; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws10000-c10-r62 | chosen-ws10000-c100-r62 |
|---|---|---|
| preLock | 0.98 / 0.80 / 1.95 / 3.78 | 0.90 / 0.75 / 1.73 / 3.55 |
| lockWait | 81.02 / 93.36 / 160.77 / 220.40 | 104.59 / 72.52 / 255.13 / 349.49 |
| hold | 14.74 / 13.59 / 23.22 / 32.05 | 14.33 / 13.17 / 22.30 / 33.88 |
| toSnapshot | 0.26 / 0.22 / 0.43 / 0.88 | 0.17 / 0.13 / 0.26 / 0.46 |
| snapshot | 10.15 / 9.30 / 15.59 / 24.36 | 10.38 / 9.47 / 15.29 / 29.28 |
| decide | 0.60 / 0.50 / 1.08 / 1.85 | 0.41 / 0.37 / 0.67 / 1.07 |
| insert | 2.30 / 1.96 / 4.30 / 7.99 | 2.12 / 1.83 / 3.65 / 7.50 |
| toCommit | 0.04 / 0.03 / 0.09 / 0.14 | 0.02 / 0.01 / 0.05 / 0.09 |
| commit | 1.39 / 1.14 / 2.76 / 5.45 | 1.22 / 1.05 / 2.09 / 3.86 |
| afterCommit | 0.05 / 0.04 / 0.12 / 0.22 | 0.02 / 0.02 / 0.05 / 0.08 |
| snapshot exec (server, mean) | 7.25 (max 53.72) | n/a |
| snapshot plans in the phase × mean plan | 28 × 12.35 | n/a |
| T1 tx p50 / p99 | 107.55 / 245.47 | 87.57 / 373.14 |
| **T1 p50 / p95 / p99** (completion − due) | 157.01 / 942.10 / 1056.84 | 89.44 / 749.26 / 1649.63 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). Every pool connection was warmed past prepareThreshold before the phase, so a plan inside it is an invalidation (autovacuum/analyze), not a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws10000-c10-r62 | 62.00 | 61.95 | 3720 / 3720 | 68.47 | 0 | 0 | 0 |
| chosen-ws10000-c100-r62 | 62.00 | 61.97 | 3720 / 3720 | 43.64 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws10000-c10-r62 | 0.914 | 771.48 | 803.52 |
| chosen-ws10000-c100-r62 | 0.888 | 575.78 | 609.66 |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws10000-c10-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 192, relallvisible 184, reltuples 1212
storage_reservation: relpages 1000, relallvisible 990, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=2298.30..2823.73 rows=21 width=498) (actual time=6.712..7.935 rows=12 loops=1)
  Buffers: shared hit=1628
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.017..0.018 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.009 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.014 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.003..0.005 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..314.52 rows=2 width=40) (actual time=0.046..0.056 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.045..0.046 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=313.48..313.50 rows=1 width=64) (actual time=0.004..0.006 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=312.47..312.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..262.31 rows=10031 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=222.22..222.23 rows=1 width=64) (actual time=0.880..0.881 rows=1 loops=1)
          Buffers: shared hit=203
          ->  Seq Scan on storage_delta  (cost=0.00..215.81 rows=1281 width=16) (actual time=0.014..0.737 rows=1948 loops=1)
                Buffers: shared hit=203
  CTE g_reserved
    ->  Aggregate  (cost=1085.68..1085.69 rows=1 width=40) (actual time=3.347..3.348 rows=1 loops=1)
          Buffers: shared hit=1067
          ->  Seq Scan on storage_reservation  (cost=0.00..1077.67 rows=1067 width=156) (actual time=0.010..3.038 rows=1000 loops=1)
                Buffers: shared hit=1067
  CTE fp_newest
    ->  Limit  (cost=1.02..1.02 rows=1 width=112) (actual time=0.126..0.127 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.02..1.02 rows=1 width=112) (actual time=0.124..0.125 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.01 rows=1 width=112) (actual time=0.012..0.013 rows=1 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..1.47 rows=32 width=33) (actual time=0.025..0.033 rows=31 loops=1)
          Buffers: shared hit=1
          ->  Seq Scan on participant_workspace  (cost=0.00..0.00 rows=1 width=33) (actual time=0.004..0.004 rows=0 loops=1)
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.31 rows=31 width=33) (actual time=0.019..0.023 rows=31 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=13.50..13.51 rows=1 width=128) (actual time=0.473..0.477 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.129..0.129 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.44..12.25 rows=122 width=32) (actual time=0.297..0.430 rows=372 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=7
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..10.47 rows=122 width=32) (actual time=0.156..0.234 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=6
                ->  Hash  (cost=1.04..1.04 rows=32 width=16) (actual time=0.087..0.088 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=1
                      ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.062..0.066 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=1
                            ->  CTE Scan on cp_shares  (cost=0.00..0.64 rows=32 width=16) (actual time=0.027..0.046 rows=31 loops=1)
                                  Buffers: shared hit=1
  CTE cp_unattributed
    ->  Aggregate  (cost=10.87..10.89 rows=1 width=64) (actual time=0.127..0.134 rows=1 loops=1)
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.016..0.016 rows=1 loops=1)
          ->  Hash Right Anti Join  (cost=0.79..10.82 rows=1 width=16) (actual time=0.100..0.105 rows=0 loops=1)
                Hash Cond: (d_4.id = c_1.debt_id)
                ->  Seq Scan on storage_deletion_debt d_4  (cost=0.00..8.65 rows=365 width=8) (never executed)
                ->  Hash  (cost=0.78..0.78 rows=1 width=24) (actual time=0.043..0.047 rows=0 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                      ->  Hash Right Anti Join  (cost=0.01..0.78 rows=1 width=24) (actual time=0.043..0.045 rows=0 loops=1)
                            Hash Cond: (s_1.workspace_id = c_1.workspace_id)
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.64 rows=32 width=16) (never executed)
                            ->  Hash  (cost=0.00..0.00 rows=1 width=40) (actual time=0.005..0.006 rows=0 loops=1)
                                  Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                  ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..0.00 rows=1 width=40) (actual time=0.004..0.004 rows=0 loops=1)
  InitPlan 14 (returns $13)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.055..0.061 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $14)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.883..0.883 rows=1 loops=1)
          Buffers: shared hit=203
  InitPlan 16 (returns $15)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=3.355..3.357 rows=1 loops=1)
          Buffers: shared hit=1067
  InitPlan 17 (returns $16)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.003..0.004 rows=1 loops=1)
  InitPlan 18 (returns $17)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 19 (returns $18)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $19)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $20)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $21)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 23 (returns $22)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $23)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $24)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $25)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.477..0.477 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 27 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $27)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.009..0.010 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $28)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.007..0.008 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $29)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $30)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 32 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 33 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.004 rows=1 loops=1)
  InitPlan 36 (returns $35)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..0.00 rows=1 width=0) (actual time=0.001..0.001 rows=0 loops=1)
  InitPlan 37 (returns $36)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..0.00 rows=1 width=0) (actual time=0.006..0.006 rows=0 loops=1)
  InitPlan 38 (returns $37)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.001 rows=0 loops=1)
          Filter: approved
  InitPlan 39 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.002 rows=0 loops=1)
          Filter: approved
  InitPlan 40 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..0.00 rows=1 width=8) (actual time=0.005..0.006 rows=0 loops=1)
  InitPlan 41 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..0.00 rows=1 width=8) (actual time=0.001..0.002 rows=0 loops=1)
  InitPlan 42 (returns $41)
    ->  Aggregate  (cost=0.75..0.76 rows=1 width=8) (actual time=0.023..0.027 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.00..0.74 rows=1 width=4) (actual time=0.018..0.020 rows=1 loops=1)
                Buffers: shared hit=1
                ->  Nested Loop Anti Join  (cost=0.00..7406.95 rows=9999 width=4) (actual time=0.016..0.017 rows=1 loops=1)
                      Join Filter: (s_2.workspace_id = w.id)
                      Rows Removed by Join Filter: 31
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..184.31 rows=10031 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                            Buffers: shared hit=1
                      ->  CTE Scan on cp_shares s_2  (cost=0.00..0.64 rows=32 width=16) (actual time=0.000..0.003 rows=31 loops=1)
  InitPlan 46 (returns $47)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..1.21 rows=1 width=1) (actual time=0.002..0.008 rows=0 loops=1)
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
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.130..0.130 rows=1 loops=1)
  InitPlan 48 (returns $49)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 49 (returns $50)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 50 (returns $54)
    ->  Aggregate  (cost=642.99..643.00 rows=1 width=32) (actual time=1.468..1.483 rows=1 loops=1)
          Buffers: shared hit=217
          ->  Hash Left Join  (cost=402.83..642.51 rows=32 width=217) (actual time=0.578..0.756 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=217
                ->  Nested Loop Left Join  (cost=157.63..397.21 rows=32 width=177) (actual time=0.361..0.524 rows=31 loops=1)
                      Buffers: shared hit=155
                      ->  Hash Right Join  (cost=157.34..159.53 rows=32 width=161) (actual time=0.328..0.345 rows=31 loops=1)
                            Hash Cond: (storage_delta_1.workspace_id = s_3.workspace_id)
                            Buffers: shared hit=62
                            ->  HashAggregate  (cost=156.16..157.14 rows=65 width=80) (actual time=0.215..0.217 rows=0 loops=1)
                                  Group Key: storage_delta_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=62
                                  ->  Nested Loop  (cost=1.00..155.67 rows=65 width=32) (actual time=0.212..0.214 rows=0 loops=1)
                                        Buffers: shared hit=62
                                        ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.015..0.020 rows=31 loops=1)
                                              Group Key: cp_shares_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.64 rows=32 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_delta_workspace on storage_delta storage_delta_1  (cost=0.28..4.81 rows=2 width=32) (actual time=0.006..0.006 rows=0 loops=31)
                                              Index Cond: (workspace_id = cp_shares_1.workspace_id)
                                              Heap Fetches: 0
                                              Buffers: shared hit=62
                            ->  Hash  (cost=0.78..0.78 rows=32 width=97) (actual time=0.078..0.083 rows=31 loops=1)
                                  Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                  ->  Hash Left Join  (cost=0.04..0.78 rows=32 width=97) (actual time=0.047..0.060 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.64 rows=32 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=0.03..0.03 rows=1 width=80) (actual time=0.006..0.010 rows=0 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                              ->  Subquery Scan on c_2  (cost=0.01..0.03 rows=1 width=80) (actual time=0.005..0.008 rows=0 loops=1)
                                                    ->  HashAggregate  (cost=0.01..0.02 rows=1 width=80) (actual time=0.004..0.006 rows=0 loops=1)
                                                          Group Key: workspace_deletion_charge_1.workspace_id
                                                          Batches: 1  Memory Usage: 24kB
                                                          ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.002 rows=0 loops=1)
                      ->  Index Scan using workspace_storage_account_pkey on workspace_storage_account a_2  (cost=0.29..7.43 rows=1 width=32) (actual time=0.005..0.005 rows=1 loops=31)
                            Index Cond: (workspace_id = s_3.workspace_id)
                            Buffers: shared hit=93
                ->  Hash  (cost=244.79..244.79 rows=33 width=56) (actual time=0.182..0.187 rows=0 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                      Buffers: shared hit=62
                      ->  Subquery Scan on r_3  (cost=243.63..244.79 rows=33 width=56) (actual time=0.182..0.186 rows=0 loops=1)
                            Buffers: shared hit=62
                            ->  GroupAggregate  (cost=243.63..244.46 rows=33 width=56) (actual time=0.181..0.185 rows=0 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Buffers: shared hit=62
                                  ->  Sort  (cost=243.63..243.72 rows=33 width=172) (actual time=0.180..0.183 rows=0 loops=1)
                                        Sort Key: storage_reservation_1.workspace_id
                                        Sort Method: quicksort  Memory: 25kB
                                        Buffers: shared hit=62
                                        ->  Nested Loop  (cost=1.00..242.80 rows=33 width=172) (actual time=0.154..0.157 rows=0 loops=1)
                                              Buffers: shared hit=62
                                              ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.020..0.025 rows=31 loops=1)
                                                    Group Key: cp_shares_2.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.64 rows=32 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                                              ->  Index Scan using ix_storage_reservation_workspace on storage_reservation storage_reservation_1  (cost=0.28..7.55 rows=1 width=172) (actual time=0.004..0.004 rows=0 loops=31)
                                                    Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                                    Buffers: shared hit=62
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=6.710..6.776 rows=1 loops=1)
        Buffers: shared hit=1500
  ->  Hash Left Join  (cost=139.10..223.67 rows=10 width=498) (actual time=0.522..0.539 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=8
        ->  Hash Left Join  (cost=55.09..139.49 rows=10 width=193) (actual time=0.361..0.375 rows=1 loops=1)
              Hash Cond: (ws.id = e.workspace_id)
              Buffers: shared hit=6
              ->  Hash Left Join  (cost=54.02..138.38 rows=10 width=192) (actual time=0.316..0.326 rows=1 loops=1)
                    Hash Cond: (ws.id = p.workspace_id)
                    Buffers: shared hit=6
                    ->  Hash Left Join  (cost=54.01..138.34 rows=10 width=176) (actual time=0.282..0.291 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=6
                          ->  Hash Left Join  (cost=53.64..137.93 rows=10 width=112) (actual time=0.222..0.227 rows=1 loops=1)
                                Hash Cond: (ws.id = d.id)
                                Buffers: shared hit=6
                                ->  Nested Loop Left Join  (cost=0.61..84.88 rows=10 width=48) (actual time=0.125..0.127 rows=1 loops=1)
                                      Buffers: shared hit=4
                                      ->  Hash Right Join  (cost=0.33..1.85 rows=10 width=32) (actual time=0.086..0.087 rows=1 loops=1)
                                            Hash Cond: (l.workspace_id = ws.id)
                                            Buffers: shared hit=1
                                            ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.31 rows=31 width=32) (actual time=0.013..0.016 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.040..0.040 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.020..0.021 rows=1 loops=1)
                                      ->  Index Scan using workspace_storage_account_pkey on workspace_storage_account a  (cost=0.29..8.30 rows=1 width=32) (actual time=0.035..0.035 rows=1 loops=1)
                                            Index Cond: (workspace_id = ws.id)
                                            Buffers: shared hit=3
                                ->  Hash  (cost=52.78..52.78 rows=20 width=80) (actual time=0.078..0.080 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=2
                                      ->  Subquery Scan on d  (cost=52.08..52.78 rows=20 width=80) (actual time=0.077..0.079 rows=0 loops=1)
                                            Buffers: shared hit=2
                                            ->  GroupAggregate  (cost=52.08..52.58 rows=20 width=80) (actual time=0.075..0.077 rows=0 loops=1)
                                                  Group Key: d_1.workspace_id
                                                  Buffers: shared hit=2
                                                  ->  Sort  (cost=52.08..52.13 rows=20 width=32) (actual time=0.074..0.075 rows=0 loops=1)
                                                        Sort Key: d_1.workspace_id
                                                        Sort Method: quicksort  Memory: 25kB
                                                        Buffers: shared hit=2
                                                        ->  Nested Loop  (cost=0.50..51.65 rows=20 width=32) (actual time=0.042..0.043 rows=0 loops=1)
                                                              Buffers: shared hit=2
                                                              ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.009 rows=1 loops=1)
                                                                    Group Key: ws_1.id
                                                                    Batches: 1  Memory Usage: 24kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                                              ->  Index Only Scan using ix_storage_delta_workspace on storage_delta d_1  (cost=0.28..5.11 rows=2 width=32) (actual time=0.031..0.031 rows=0 loops=1)
                                                                    Index Cond: (workspace_id = ws_1.id)
                                                                    Heap Fetches: 0
                                                                    Buffers: shared hit=2
                          ->  Hash  (cost=0.36..0.36 rows=1 width=80) (actual time=0.009..0.011 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                ->  Subquery Scan on c  (cost=0.33..0.36 rows=1 width=80) (actual time=0.008..0.010 rows=0 loops=1)
                                      ->  HashAggregate  (cost=0.33..0.35 rows=1 width=80) (actual time=0.006..0.008 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  Nested Loop Semi Join  (cost=0.00..0.33 rows=1 width=32) (actual time=0.004..0.005 rows=0 loops=1)
                                                  Join Filter: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..0.00 rows=1 width=32) (actual time=0.003..0.004 rows=0 loops=1)
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (never executed)
                    ->  Hash  (cost=0.00..0.00 rows=1 width=32) (actual time=0.006..0.006 rows=0 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 8kB
                          ->  Seq Scan on participant_workspace p  (cost=0.00..0.00 rows=1 width=32) (actual time=0.005..0.005 rows=0 loops=1)
              ->  Hash  (cost=1.05..1.05 rows=1 width=17) (actual time=0.005..0.009 rows=0 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 8kB
                    ->  Subquery Scan on e  (cost=1.01..1.05 rows=1 width=17) (actual time=0.004..0.008 rows=0 loops=1)
                          ->  Nested Loop Left Join  (cost=1.01..1.04 rows=1 width=17) (actual time=0.003..0.006 rows=0 loops=1)
                                Join Filter: (h.id = a_1.account_id)
                                InitPlan 51 (returns $55)
                                  ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (never executed)
                                ->  Nested Loop Left Join  (cost=0.00..0.01 rows=1 width=48) (actual time=0.002..0.003 rows=0 loops=1)
                                      Join Filter: ((a_1.cohort_id = p_1.cohort_id) AND (a_1.place = p_1.place))
                                      ->  Seq Scan on participant_workspace p_1  (cost=0.00..0.00 rows=1 width=52) (actual time=0.001..0.002 rows=0 loops=1)
                                      ->  Seq Scan on beta_admission a_1  (cost=0.00..0.00 rows=1 width=68) (never executed)
                                ->  Seq Scan on human_account h  (cost=0.00..0.00 rows=1 width=56) (never executed)
        ->  Hash  (cost=83.89..83.89 rows=10 width=56) (actual time=0.060..0.062 rows=0 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 8kB
              Buffers: shared hit=2
              ->  Subquery Scan on r  (cost=83.54..83.89 rows=10 width=56) (actual time=0.059..0.061 rows=0 loops=1)
                    Buffers: shared hit=2
                    ->  GroupAggregate  (cost=83.54..83.79 rows=10 width=56) (actual time=0.058..0.060 rows=0 loops=1)
                          Group Key: r_1.workspace_id
                          Buffers: shared hit=2
                          ->  Sort  (cost=83.54..83.57 rows=10 width=172) (actual time=0.057..0.058 rows=0 loops=1)
                                Sort Key: r_1.workspace_id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=2
                                ->  Nested Loop  (cost=0.50..83.38 rows=10 width=172) (actual time=0.030..0.031 rows=0 loops=1)
                                      Buffers: shared hit=2
                                      ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                                            Group Key: ws_3.id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                      ->  Index Scan using ix_storage_reservation_workspace on storage_reservation r_1  (cost=0.28..8.30 rows=1 width=172) (actual time=0.020..0.020 rows=0 loops=1)
                                            Index Cond: (workspace_id = ws_3.id)
                                            Buffers: shared hit=2
  ->  Merge Left Join  (cost=132.48..301.64 rows=10 width=498) (actual time=0.357..0.542 rows=10 loops=1)
        Merge Cond: (ib.id = r_2.inbox_id)
        Buffers: shared hit=120
        ->  Nested Loop Left Join  (cost=48.84..217.38 rows=10 width=112) (actual time=0.232..0.407 rows=10 loops=1)
              Buffers: shared hit=100
              ->  Nested Loop Left Join  (cost=48.43..133.03 rows=10 width=96) (actual time=0.197..0.287 rows=10 loops=1)
                    Buffers: shared hit=60
                    ->  Merge Left Join  (cost=48.01..48.68 rows=10 width=80) (actual time=0.165..0.170 rows=10 loops=1)
                          Merge Cond: (ib.id = d_2.inbox_id)
                          Buffers: shared hit=20
                          ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.055..0.056 rows=10 loops=1)
                                Sort Key: ib.id
                                Sort Method: quicksort  Memory: 25kB
                                ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.015..0.025 rows=10 loops=1)
                          ->  GroupAggregate  (cost=47.64..47.99 rows=14 width=80) (actual time=0.106..0.108 rows=0 loops=1)
                                Group Key: d_2.inbox_id
                                Buffers: shared hit=20
                                ->  Sort  (cost=47.64..47.68 rows=14 width=32) (actual time=0.105..0.106 rows=0 loops=1)
                                      Sort Key: d_2.inbox_id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=20
                                      ->  Nested Loop  (cost=0.50..47.38 rows=14 width=32) (actual time=0.083..0.084 rows=0 loops=1)
                                            Buffers: shared hit=20
                                            ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.011 rows=10 loops=1)
                                                  Group Key: ib_1.id
                                                  Batches: 1  Memory Usage: 24kB
                                                  ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                            ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..4.70 rows=1 width=32) (actual time=0.007..0.007 rows=0 loops=10)
                                                  Index Cond: (inbox_id = ib_1.id)
                                                  Heap Fetches: 0
                                                  Buffers: shared hit=20
                    ->  Index Scan using inbox_pkey on inbox x  (cost=0.42..8.44 rows=1 width=32) (actual time=0.011..0.011 rows=1 loops=10)
                          Index Cond: (id = ib.id)
                          Buffers: shared hit=40
              ->  Index Scan using inbox_storage_pkey on inbox_storage s  (cost=0.42..8.44 rows=1 width=32) (actual time=0.011..0.011 rows=1 loops=10)
                    Index Cond: (inbox_id = ib.id)
                    Buffers: shared hit=40
        ->  GroupAggregate  (cost=83.64..83.89 rows=10 width=56) (actual time=0.115..0.117 rows=0 loops=1)
              Group Key: r_2.inbox_id
              Buffers: shared hit=20
              ->  Sort  (cost=83.64..83.66 rows=10 width=172) (actual time=0.114..0.115 rows=0 loops=1)
                    Sort Key: r_2.inbox_id
                    Sort Method: quicksort  Memory: 25kB
                    Buffers: shared hit=20
                    ->  Nested Loop  (cost=0.50..83.47 rows=10 width=172) (actual time=0.087..0.088 rows=0 loops=1)
                          Buffers: shared hit=20
                          ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                                Group Key: ib_2.id
                                Batches: 1  Memory Usage: 24kB
                                ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                          ->  Index Scan using ix_storage_reservation_inbox on storage_reservation r_2  (cost=0.28..8.30 rows=1 width=172) (actual time=0.007..0.007 rows=0 loops=10)
                                Index Cond: (inbox_id = ib_2.id)
                                Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.146 ms
Execution Time: 11.142 ms
```

## chosen-ws10000-c100-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 241, relallvisible 230, reltuples 1808
storage_reservation: relpages 1906, relallvisible 1894, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=3191.87..3717.69 rows=21 width=498) (actual time=7.130..8.380 rows=12 loops=1)
  Buffers: shared hit=2514
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.021..0.022 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.013..0.015 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.006 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..314.52 rows=2 width=40) (actual time=0.025..0.035 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.025..0.026 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=313.48..313.50 rows=1 width=64) (actual time=0.004..0.006 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.003..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=312.47..312.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..262.31 rows=10031 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=268.12..268.13 rows=1 width=64) (actual time=1.075..1.076 rows=1 loops=1)
          Buffers: shared hit=241
          ->  Seq Scan on storage_delta  (cost=0.00..259.08 rows=1808 width=16) (actual time=0.017..0.850 rows=1988 loops=1)
                Buffers: shared hit=241
  CTE g_reserved
    ->  Aggregate  (cost=1923.50..1923.51 rows=1 width=40) (actual time=3.181..3.182 rows=1 loops=1)
          Buffers: shared hit=1906
          ->  Seq Scan on storage_reservation  (cost=0.00..1916.00 rows=1000 width=156) (actual time=0.007..2.847 rows=1000 loops=1)
                Buffers: shared hit=1906
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.132..0.134 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.129..0.130 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.011..0.012 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..1.47 rows=32 width=33) (actual time=0.019..0.029 rows=31 loops=1)
          Buffers: shared hit=1
          ->  Seq Scan on participant_workspace  (cost=0.00..0.00 rows=1 width=33) (actual time=0.005..0.006 rows=0 loops=1)
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.31 rows=31 width=33) (actual time=0.011..0.016 rows=31 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=20.94..20.95 rows=1 width=128) (actual time=0.621..0.628 rows=1 loops=1)
          Buffers: shared hit=16
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.135..0.136 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.44..19.96 rows=95 width=32) (actual time=0.348..0.563 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=16
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..18.26 rows=95 width=32) (actual time=0.162..0.304 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=15
                ->  Hash  (cost=1.04..1.04 rows=32 width=16) (actual time=0.122..0.125 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=1
                      ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.068..0.074 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=1
                            ->  CTE Scan on cp_shares  (cost=0.00..0.64 rows=32 width=16) (actual time=0.021..0.046 rows=31 loops=1)
                                  Buffers: shared hit=1
  CTE cp_unattributed
    ->  Aggregate  (cost=7.80..7.82 rows=1 width=64) (actual time=0.059..0.065 rows=1 loops=1)
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.29..7.75 rows=1 width=16) (actual time=0.049..0.053 rows=0 loops=1)
                ->  Hash Right Anti Join  (cost=0.01..0.78 rows=1 width=24) (actual time=0.048..0.050 rows=0 loops=1)
                      Hash Cond: (s_1.workspace_id = c_1.workspace_id)
                      ->  CTE Scan on cp_shares s_1  (cost=0.00..0.64 rows=32 width=16) (never executed)
                      ->  Hash  (cost=0.00..0.00 rows=1 width=40) (actual time=0.004..0.005 rows=0 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                            ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..0.00 rows=1 width=40) (actual time=0.004..0.004 rows=0 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.27..4.29 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.033..0.038 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=1.077..1.078 rows=1 loops=1)
          Buffers: shared hit=241
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=3.190..3.193 rows=1 loops=1)
          Buffers: shared hit=1906
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=1)
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
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.625..0.626 rows=1 loops=1)
          Buffers: shared hit=16
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.010..0.011 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..0.00 rows=1 width=0) (actual time=0.012..0.012 rows=0 loops=1)
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..0.00 rows=1 width=0) (actual time=0.002..0.003 rows=0 loops=1)
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..0.00 rows=1 width=32) (actual time=0.002..0.002 rows=0 loops=1)
          Filter: approved
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.001 rows=0 loops=1)
          Filter: approved
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..0.00 rows=1 width=8) (actual time=0.005..0.005 rows=0 loops=1)
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..0.00 rows=1 width=8) (actual time=0.002..0.002 rows=0 loops=1)
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=0.75..0.76 rows=1 width=8) (actual time=0.024..0.027 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.00..0.74 rows=1 width=4) (actual time=0.019..0.021 rows=1 loops=1)
                Buffers: shared hit=1
                ->  Nested Loop Anti Join  (cost=0.00..7406.95 rows=9999 width=4) (actual time=0.018..0.019 rows=1 loops=1)
                      Join Filter: (s_2.workspace_id = w.id)
                      Rows Removed by Join Filter: 31
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..184.31 rows=10031 width=16) (actual time=0.006..0.006 rows=1 loops=1)
                            Buffers: shared hit=1
                      ->  CTE Scan on cp_shares s_2  (cost=0.00..0.64 rows=32 width=16) (actual time=0.000..0.004 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..1.21 rows=1 width=1) (actual time=0.002..0.005 rows=0 loops=1)
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
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.062..0.062 rows=1 loops=1)
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $55)
    ->  Aggregate  (cost=648.45..648.46 rows=1 width=32) (actual time=1.751..1.765 rows=1 loops=1)
          Buffers: shared hit=217
          ->  Hash Left Join  (cost=407.67..647.97 rows=32 width=217) (actual time=0.673..0.885 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=217
                ->  Hash Left Join  (cost=154.55..394.75 rows=32 width=177) (actual time=0.406..0.603 rows=31 loops=1)
                      Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=155
                      ->  Nested Loop Left Join  (cost=154.51..394.61 rows=32 width=113) (actual time=0.355..0.537 rows=31 loops=1)
                            Buffers: shared hit=155
                            ->  Hash Right Join  (cost=154.22..156.93 rows=32 width=97) (actual time=0.313..0.328 rows=31 loops=1)
                                  Hash Cond: (storage_delta_1.workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=62
                                  ->  HashAggregate  (cost=153.18..154.43 rows=83 width=80) (actual time=0.232..0.234 rows=0 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=62
                                        ->  Nested Loop  (cost=1.00..152.56 rows=83 width=32) (actual time=0.228..0.230 rows=0 loops=1)
                                              Buffers: shared hit=62
                                              ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.018..0.026 rows=31 loops=1)
                                                    Group Key: cp_shares_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.64 rows=32 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                                              ->  Index Only Scan using ix_storage_delta_workspace on storage_delta storage_delta_1  (cost=0.28..4.70 rows=3 width=32) (actual time=0.006..0.006 rows=0 loops=31)
                                                    Index Cond: (workspace_id = cp_shares_1.workspace_id)
                                                    Heap Fetches: 0
                                                    Buffers: shared hit=62
                                  ->  Hash  (cost=0.64..0.64 rows=32 width=33) (actual time=0.035..0.036 rows=31 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.64 rows=32 width=33) (actual time=0.002..0.008 rows=31 loops=1)
                            ->  Index Scan using workspace_storage_account_pkey on workspace_storage_account a_2  (cost=0.29..7.43 rows=1 width=32) (actual time=0.006..0.006 rows=1 loops=31)
                                  Index Cond: (workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=93
                      ->  Hash  (cost=0.03..0.03 rows=1 width=80) (actual time=0.010..0.013 rows=0 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                            ->  Subquery Scan on c_2  (cost=0.01..0.03 rows=1 width=80) (actual time=0.010..0.011 rows=0 loops=1)
                                  ->  HashAggregate  (cost=0.01..0.02 rows=1 width=80) (actual time=0.009..0.010 rows=0 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..0.00 rows=1 width=32) (actual time=0.007..0.007 rows=0 loops=1)
                ->  Hash  (cost=252.71..252.71 rows=33 width=56) (actual time=0.220..0.224 rows=0 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                      Buffers: shared hit=62
                      ->  Subquery Scan on r_3  (cost=251.55..252.71 rows=33 width=56) (actual time=0.220..0.224 rows=0 loops=1)
                            Buffers: shared hit=62
                            ->  GroupAggregate  (cost=251.55..252.38 rows=33 width=56) (actual time=0.218..0.221 rows=0 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Buffers: shared hit=62
                                  ->  Sort  (cost=251.55..251.64 rows=33 width=172) (actual time=0.216..0.219 rows=0 loops=1)
                                        Sort Key: storage_reservation_1.workspace_id
                                        Sort Method: quicksort  Memory: 25kB
                                        Buffers: shared hit=62
                                        ->  Nested Loop  (cost=0.99..250.72 rows=33 width=172) (actual time=0.181..0.183 rows=0 loops=1)
                                              Buffers: shared hit=62
                                              ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.021..0.028 rows=31 loops=1)
                                                    Group Key: cp_shares_2.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.64 rows=32 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                                              ->  Index Scan using ix_storage_reservation_workspace on storage_reservation storage_reservation_1  (cost=0.28..7.79 rows=1 width=172) (actual time=0.005..0.005 rows=0 loops=31)
                                                    Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                                    Buffers: shared hit=62
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=7.117..7.189 rows=1 loops=1)
        Buffers: shared hit=2386
  ->  Hash Left Join  (cost=139.14..223.68 rows=10 width=498) (actual time=0.468..0.484 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=8
        ->  Hash Left Join  (cost=55.15..139.53 rows=10 width=193) (actual time=0.354..0.367 rows=1 loops=1)
              Hash Cond: (ws.id = e.workspace_id)
              Buffers: shared hit=6
              ->  Hash Left Join  (cost=54.08..138.42 rows=10 width=192) (actual time=0.309..0.318 rows=1 loops=1)
                    Hash Cond: (ws.id = p.workspace_id)
                    Buffers: shared hit=6
                    ->  Hash Left Join  (cost=54.07..138.38 rows=10 width=176) (actual time=0.270..0.278 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=6
                          ->  Nested Loop Left Join  (cost=53.70..137.97 rows=10 width=112) (actual time=0.222..0.227 rows=1 loops=1)
                                Buffers: shared hit=6
                                ->  Hash Right Join  (cost=53.42..54.94 rows=10 width=96) (actual time=0.191..0.195 rows=1 loops=1)
                                      Hash Cond: (l.workspace_id = ws.id)
                                      Buffers: shared hit=3
                                      ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.31 rows=31 width=32) (actual time=0.014..0.017 rows=31 loops=1)
                                            Buffers: shared hit=1
                                      ->  Hash  (cost=53.29..53.29 rows=10 width=80) (actual time=0.129..0.132 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=2
                                            ->  Hash Right Join  (cost=52.44..53.29 rows=10 width=80) (actual time=0.117..0.120 rows=1 loops=1)
                                                  Hash Cond: (d.workspace_id = ws.id)
                                                  Buffers: shared hit=2
                                                  ->  HashAggregate  (cost=52.12..52.51 rows=26 width=80) (actual time=0.039..0.041 rows=0 loops=1)
                                                        Group Key: d.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=2
                                                        ->  Nested Loop  (cost=0.50..51.92 rows=26 width=32) (actual time=0.037..0.039 rows=0 loops=1)
                                                              Buffers: shared hit=2
                                                              ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.007 rows=1 loops=1)
                                                                    Group Key: ws_1.id
                                                                    Batches: 1  Memory Usage: 24kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                                              ->  Index Only Scan using ix_storage_delta_workspace on storage_delta d  (cost=0.28..5.13 rows=3 width=32) (actual time=0.028..0.028 rows=0 loops=1)
                                                                    Index Cond: (workspace_id = ws_1.id)
                                                                    Heap Fetches: 0
                                                                    Buffers: shared hit=2
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.039..0.040 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.024..0.025 rows=1 loops=1)
                                ->  Index Scan using workspace_storage_account_pkey on workspace_storage_account a  (cost=0.29..8.30 rows=1 width=32) (actual time=0.027..0.027 rows=1 loops=1)
                                      Index Cond: (workspace_id = ws.id)
                                      Buffers: shared hit=3
                          ->  Hash  (cost=0.36..0.36 rows=1 width=80) (actual time=0.007..0.009 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                ->  Subquery Scan on c  (cost=0.33..0.36 rows=1 width=80) (actual time=0.006..0.008 rows=0 loops=1)
                                      ->  HashAggregate  (cost=0.33..0.35 rows=1 width=80) (actual time=0.005..0.007 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  Nested Loop Semi Join  (cost=0.00..0.33 rows=1 width=32) (actual time=0.003..0.004 rows=0 loops=1)
                                                  Join Filter: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..0.00 rows=1 width=32) (actual time=0.002..0.003 rows=0 loops=1)
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (never executed)
                    ->  Hash  (cost=0.00..0.00 rows=1 width=32) (actual time=0.003..0.004 rows=0 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 8kB
                          ->  Seq Scan on participant_workspace p  (cost=0.00..0.00 rows=1 width=32) (actual time=0.003..0.003 rows=0 loops=1)
              ->  Hash  (cost=1.05..1.05 rows=1 width=17) (actual time=0.005..0.008 rows=0 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 8kB
                    ->  Subquery Scan on e  (cost=1.01..1.05 rows=1 width=17) (actual time=0.004..0.007 rows=0 loops=1)
                          ->  Nested Loop Left Join  (cost=1.01..1.04 rows=1 width=17) (actual time=0.004..0.006 rows=0 loops=1)
                                Join Filter: (h.id = a_1.account_id)
                                InitPlan 51 (returns $56)
                                  ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (never executed)
                                ->  Nested Loop Left Join  (cost=0.00..0.01 rows=1 width=48) (actual time=0.002..0.003 rows=0 loops=1)
                                      Join Filter: ((a_1.cohort_id = p_1.cohort_id) AND (a_1.place = p_1.place))
                                      ->  Seq Scan on participant_workspace p_1  (cost=0.00..0.00 rows=1 width=52) (actual time=0.001..0.002 rows=0 loops=1)
                                      ->  Seq Scan on beta_admission a_1  (cost=0.00..0.00 rows=1 width=68) (never executed)
                                ->  Seq Scan on human_account h  (cost=0.00..0.00 rows=1 width=56) (never executed)
        ->  Hash  (cost=83.87..83.87 rows=10 width=56) (actual time=0.070..0.073 rows=0 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 8kB
              Buffers: shared hit=2
              ->  Subquery Scan on r  (cost=83.52..83.87 rows=10 width=56) (actual time=0.070..0.072 rows=0 loops=1)
                    Buffers: shared hit=2
                    ->  GroupAggregate  (cost=83.52..83.77 rows=10 width=56) (actual time=0.069..0.070 rows=0 loops=1)
                          Group Key: r_1.workspace_id
                          Buffers: shared hit=2
                          ->  Sort  (cost=83.52..83.54 rows=10 width=172) (actual time=0.067..0.069 rows=0 loops=1)
                                Sort Key: r_1.workspace_id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=2
                                ->  Nested Loop  (cost=0.50..83.35 rows=10 width=172) (actual time=0.036..0.036 rows=0 loops=1)
                                      Buffers: shared hit=2
                                      ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                                            Group Key: ws_3.id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                      ->  Index Scan using ix_storage_reservation_workspace on storage_reservation r_1  (cost=0.28..8.29 rows=1 width=172) (actual time=0.026..0.026 rows=0 loops=1)
                                            Index Cond: (workspace_id = ws_3.id)
                                            Buffers: shared hit=2
  ->  Merge Left Join  (cost=132.78..302.02 rows=10 width=498) (actual time=0.443..0.616 rows=10 loops=1)
        Merge Cond: (ib.id = r_2.inbox_id)
        Buffers: shared hit=120
        ->  Nested Loop Left Join  (cost=49.17..217.79 rows=10 width=112) (actual time=0.319..0.482 rows=10 loops=1)
              Buffers: shared hit=100
              ->  Nested Loop Left Join  (cost=48.75..133.44 rows=10 width=96) (actual time=0.284..0.368 rows=10 loops=1)
                    Buffers: shared hit=60
                    ->  Merge Left Join  (cost=48.34..49.09 rows=10 width=80) (actual time=0.222..0.227 rows=10 loops=1)
                          Merge Cond: (ib.id = d_1.inbox_id)
                          Buffers: shared hit=20
                          ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.075..0.077 rows=10 loops=1)
                                Sort Key: ib.id
                                Sort Method: quicksort  Memory: 25kB
                                ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.015..0.020 rows=10 loops=1)
                          ->  GroupAggregate  (cost=47.97..48.37 rows=16 width=80) (actual time=0.142..0.144 rows=0 loops=1)
                                Group Key: d_1.inbox_id
                                Buffers: shared hit=20
                                ->  Sort  (cost=47.97..48.01 rows=16 width=32) (actual time=0.141..0.142 rows=0 loops=1)
                                      Sort Key: d_1.inbox_id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=20
                                      ->  Nested Loop  (cost=0.50..47.65 rows=16 width=32) (actual time=0.110..0.111 rows=0 loops=1)
                                            Buffers: shared hit=20
                                            ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.012 rows=10 loops=1)
                                                  Group Key: ib_1.id
                                                  Batches: 1  Memory Usage: 24kB
                                                  ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                            ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..4.71 rows=2 width=32) (actual time=0.009..0.009 rows=0 loops=10)
                                                  Index Cond: (inbox_id = ib_1.id)
                                                  Heap Fetches: 0
                                                  Buffers: shared hit=20
                    ->  Index Scan using inbox_pkey on inbox x  (cost=0.42..8.44 rows=1 width=32) (actual time=0.011..0.011 rows=1 loops=10)
                          Index Cond: (id = ib.id)
                          Buffers: shared hit=40
              ->  Index Scan using inbox_storage_pkey on inbox_storage s  (cost=0.42..8.44 rows=1 width=32) (actual time=0.010..0.010 rows=1 loops=10)
                    Index Cond: (inbox_id = ib.id)
                    Buffers: shared hit=40
        ->  GroupAggregate  (cost=83.61..83.86 rows=10 width=56) (actual time=0.113..0.114 rows=0 loops=1)
              Group Key: r_2.inbox_id
              Buffers: shared hit=20
              ->  Sort  (cost=83.61..83.64 rows=10 width=172) (actual time=0.111..0.113 rows=0 loops=1)
                    Sort Key: r_2.inbox_id
                    Sort Method: quicksort  Memory: 25kB
                    Buffers: shared hit=20
                    ->  Nested Loop  (cost=0.50..83.44 rows=10 width=172) (actual time=0.078..0.079 rows=0 loops=1)
                          Buffers: shared hit=20
                          ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.012 rows=10 loops=1)
                                Group Key: ib_2.id
                                Batches: 1  Memory Usage: 24kB
                                ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                          ->  Index Scan using ix_storage_reservation_inbox on storage_reservation r_2  (cost=0.28..8.30 rows=1 width=172) (actual time=0.006..0.006 rows=0 loops=10)
                                Index Cond: (inbox_id = ib_2.id)
                                Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.199 ms
Execution Time: 11.616 ms
```
