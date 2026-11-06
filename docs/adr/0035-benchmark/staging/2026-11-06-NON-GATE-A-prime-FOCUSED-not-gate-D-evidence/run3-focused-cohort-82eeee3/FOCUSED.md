# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [62.0]; concurrency [10, 100]; the beta's production state: the closed cohort of 31 workspaces (30 participants + 1 legacy), its pool reserved, approved and REQUIRED, every event to those workspaces; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws31-c10-r62 | chosen-ws31-c100-r62 |
|---|---|---|
| preLock | 0.91 / 0.78 / 1.68 / 2.63 | 0.92 / 0.78 / 1.78 / 3.13 |
| lockWait | 157.77 / 150.65 / 232.71 / 273.27 | 314.71 / 300.18 / 443.69 / 535.99 |
| hold | 19.70 / 18.34 / 29.54 / 38.73 | 22.15 / 19.97 / 37.99 / 51.28 |
| toSnapshot | 0.26 / 0.24 / 0.43 / 0.65 | 0.32 / 0.13 / 0.27 / 9.78 |
| snapshot | 15.04 / 13.84 / 23.63 / 31.80 | 17.22 / 15.38 / 32.43 / 41.42 |
| decide | 0.79 / 0.70 / 1.28 / 1.82 | 0.54 / 0.48 / 0.91 / 1.53 |
| insert | 2.28 / 2.11 / 3.46 / 5.70 | 2.56 / 2.26 / 4.70 / 7.07 |
| toCommit | 0.04 / 0.03 / 0.09 / 0.13 | 0.02 / 0.02 / 0.05 / 0.08 |
| commit | 1.28 / 1.10 / 2.07 / 3.26 | 1.48 / 1.23 / 2.87 / 5.30 |
| afterCommit | 0.05 / 0.04 / 0.12 / 0.20 | 0.02 / 0.02 / 0.04 / 0.07 |
| snapshot exec (server, mean) | 12.49 (max 45.20) | n/a |
| snapshot plans in the phase × mean plan | 22 × 14.23 | n/a |
| T1 tx p50 / p99 | 169.77 / 304.67 | 322.33 / 566.33 |
| **T1 p50 / p95 / p99** (completion − due) | 9296.55 / 13668.62 / 14036.86 | 9778.74 / 20191.85 / 23351.26 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). Every pool connection was warmed past prepareThreshold before the phase, so a plan inside it is an invalidation (autovacuum/analyze), not a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws31-c10-r62 | 62.00 | 50.22 | 3720 / 3720 | 14091.39 | 0 | 0 | 0 |
| chosen-ws31-c100-r62 | 62.00 | 44.73 | 3607 / 3720 | 20650.16 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws31-c10-r62 | 1.221 | unstable (ρ ≥ 1) | - |
| chosen-ws31-c100-r62 | 1.373 | unstable (ρ ≥ 1) | - |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws31-c10-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 225, relallvisible 214, reltuples 1796
storage_reservation: relpages 1169, relallvisible 1140, reltuples 1049
generic_plans = 7, custom_plans = 0
Append  (cost=2988.66..4717.22 rows=21 width=498) (actual time=11.857..15.620 rows=12 loops=1)
  Buffers: shared hit=3363
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.018 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.007..0.009 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.020..0.023 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.009..0.011 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.025..0.036 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.024..0.025 rows=1 loops=1)
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
    ->  Aggregate  (cost=251.94..251.95 rows=1 width=64) (actual time=1.148..1.149 rows=1 loops=1)
          Buffers: shared hit=225
          ->  Seq Scan on storage_delta  (cost=0.00..242.96 rows=1796 width=16) (actual time=0.265..0.924 rows=2420 loops=1)
                Buffers: shared hit=225
  CTE g_reserved
    ->  Aggregate  (cost=1187.36..1187.37 rows=1 width=40) (actual time=2.791..2.792 rows=1 loops=1)
          Buffers: shared hit=1169
          ->  Seq Scan on storage_reservation  (cost=0.00..1179.49 rows=1049 width=163) (actual time=0.008..2.453 rows=1000 loops=1)
                Buffers: shared hit=1169
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.098..0.100 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.096..0.097 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.008..0.009 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.012..0.026 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.010..0.014 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.006 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=13.36..13.37 rows=1 width=128) (actual time=0.486..0.492 rows=1 loops=1)
          Buffers: shared hit=9
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.104..0.105 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..12.30 rows=103 width=32) (actual time=0.255..0.440 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=9
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..10.61 rows=103 width=32) (actual time=0.127..0.230 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=7
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.079..0.081 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.055..0.060 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.014..0.038 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=10.20..10.22 rows=1 width=64) (actual time=0.184..0.191 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..10.15 rows=1 width=16) (actual time=0.173..0.178 rows=0 loops=1)
                Buffers: shared hit=4
                ->  Hash Anti Join  (cost=1.01..9.89 rows=1 width=24) (actual time=0.173..0.176 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=4
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..7.55 rows=355 width=40) (actual time=0.007..0.039 rows=372 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.025..0.026 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.15..0.26 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.030..0.037 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=1.151..1.152 rows=1 loops=1)
          Buffers: shared hit=225
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=2.797..2.797 rows=1 loops=1)
          Buffers: shared hit=1169
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.489..0.490 rows=1 loops=1)
          Buffers: shared hit=9
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.011..0.012 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.093..0.098 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.089..0.093 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.088..0.091 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.006..0.009 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.023..0.025 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.103..0.109 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.072..0.074 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.036..0.036 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.013..0.016 rows=2 loops=2)
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
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.187..0.187 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=1504.87..1504.88 rows=1 width=32) (actual time=6.679..6.703 rows=1 loops=1)
          Buffers: shared hit=1399
          ->  Hash Left Join  (cost=1503.60..1504.40 rows=31 width=217) (actual time=6.058..6.133 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=1399
                ->  Merge Left Join  (cost=297.10..297.82 rows=31 width=177) (actual time=2.338..2.395 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=230
                      ->  Merge Left Join  (cost=286.85..287.47 rows=31 width=113) (actual time=2.143..2.187 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=226
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.161..0.172 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.086..0.100 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.004 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.047..0.048 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.013..0.018 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=283.68..283.75 rows=31 width=80) (actual time=1.973..1.985 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=225
                                  ->  Subquery Scan on d_4  (cost=282.13..282.91 rows=31 width=80) (actual time=1.897..1.915 rows=31 loops=1)
                                        Buffers: shared hit=225
                                        ->  HashAggregate  (cost=282.13..282.60 rows=31 width=80) (actual time=1.895..1.908 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=225
                                              ->  Hash Semi Join  (cost=1.01..268.66 rows=1796 width=32) (actual time=0.267..1.390 rows=2420 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=225
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..242.96 rows=1796 width=32) (actual time=0.188..0.796 rows=2420 loops=1)
                                                          Buffers: shared hit=225
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.032..0.033 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.007 rows=31 loops=1)
                      ->  Sort  (cost=10.25..10.25 rows=1 width=80) (actual time=0.188..0.192 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=4
                            ->  Subquery Scan on c_2  (cost=10.21..10.24 rows=1 width=80) (actual time=0.141..0.143 rows=1 loops=1)
                                  Buffers: shared hit=4
                                  ->  HashAggregate  (cost=10.21..10.23 rows=1 width=80) (actual time=0.140..0.141 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=4
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..7.55 rows=355 width=32) (actual time=0.009..0.048 rows=372 loops=1)
                                              Buffers: shared hit=4
                ->  Hash  (cost=1206.11..1206.11 rows=31 width=56) (actual time=3.649..3.655 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=1169
                      ->  Subquery Scan on r_3  (cost=1205.41..1206.11 rows=31 width=56) (actual time=3.521..3.545 rows=31 loops=1)
                            Buffers: shared hit=1169
                            ->  HashAggregate  (cost=1205.41..1205.80 rows=31 width=56) (actual time=3.517..3.536 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=1169
                                  ->  Hash Semi Join  (cost=1.01..1194.92 rows=1049 width=179) (actual time=0.100..3.259 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=1169
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..1179.49 rows=1049 width=179) (actual time=0.014..2.718 rows=1000 loops=1)
                                              Buffers: shared hit=1169
                                        ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.039..0.040 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.008 rows=31 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=11.856..11.909 rows=1 loops=1)
        Buffers: shared hit=2821
  ->  Hash Right Join  (cost=1460.26..1461.38 rows=10 width=498) (actual time=2.228..2.273 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=275
        ->  HashAggregate  (cost=258.78..259.25 rows=31 width=80) (actual time=1.234..1.237 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=225
              ->  Hash Semi Join  (cost=0.33..254.44 rows=579 width=32) (actual time=0.370..1.213 rows=48 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=225
                    ->  Seq Scan on storage_delta d  (cost=0.00..242.96 rows=1796 width=32) (actual time=0.238..0.861 rows=2420 loops=1)
                          Buffers: shared hit=225
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.018..0.019 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
        ->  Hash  (cost=1201.35..1201.35 rows=10 width=169) (actual time=0.961..0.999 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=50
              ->  Merge Left Join  (cost=1201.00..1201.35 rows=10 width=169) (actual time=0.947..0.984 rows=1 loops=1)
                    Merge Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=50
                    ->  Merge Left Join  (cost=1199.98..1200.29 rows=10 width=153) (actual time=0.887..0.922 rows=1 loops=1)
                          Merge Cond: (ws.id = r.id)
                          Buffers: shared hit=49
                          ->  Sort  (cost=25.93..25.96 rows=10 width=113) (actual time=0.643..0.672 rows=1 loops=1)
                                Sort Key: ws.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=10
                                ->  Hash Left Join  (cost=23.50..25.76 rows=10 width=113) (actual time=0.544..0.605 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=10
                                      ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.356..0.409 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=6
                                            ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.172..0.211 rows=30 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $53)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.007 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.077..0.103 rows=30 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.002..0.005 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.034..0.042 rows=30 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.009..0.013 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.046..0.047 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.011..0.016 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.165..0.171 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=2
                                                  ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.148..0.159 rows=1 loops=1)
                                                        Hash Cond: (a.workspace_id = ws.id)
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.010..0.013 rows=31 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.104..0.107 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.085..0.095 rows=1 loops=1)
                                                                    Hash Cond: (p.workspace_id = ws.id)
                                                                    Buffers: shared hit=1
                                                                    ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.021..0.023 rows=30 loops=1)
                                                                          Buffers: shared hit=1
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.029..0.030 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.019..0.019 rows=1 loops=1)
                                      ->  Hash  (cost=15.44..15.44 rows=1 width=80) (actual time=0.153..0.159 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=4
                                            ->  Subquery Scan on c  (cost=15.42..15.44 rows=1 width=80) (actual time=0.152..0.158 rows=0 loops=1)
                                                  Buffers: shared hit=4
                                                  ->  HashAggregate  (cost=15.42..15.43 rows=1 width=80) (actual time=0.151..0.156 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=4
                                                        ->  Hash Semi Join  (cost=0.33..12.76 rows=355 width=32) (actual time=0.147..0.151 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                              Buffers: shared hit=4
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..7.55 rows=355 width=32) (actual time=0.008..0.049 rows=372 loops=1)
                                                                    Buffers: shared hit=4
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.016..0.017 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                          ->  Sort  (cost=1174.05..1174.13 rows=31 width=56) (actual time=0.238..0.243 rows=1 loops=1)
                                Sort Key: r.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=39
                                ->  Subquery Scan on r  (cost=1172.59..1173.29 rows=31 width=56) (actual time=0.192..0.196 rows=1 loops=1)
                                      Buffers: shared hit=39
                                      ->  HashAggregate  (cost=1172.59..1172.98 rows=31 width=56) (actual time=0.191..0.194 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=39
                                            ->  Nested Loop  (cost=10.77..1169.21 rows=338 width=179) (actual time=0.066..0.173 rows=31 loops=1)
                                                  Buffers: shared hit=39
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=1 loops=1)
                                                        Group Key: ws_3.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_1  (cost=10.54..116.55 rows=34 width=179) (actual time=0.047..0.140 rows=31 loops=1)
                                                        Recheck Cond: (workspace_id = ws_3.id)
                                                        Heap Blocks: exact=37
                                                        Buffers: shared hit=39
                                                        ->  Bitmap Index Scan on ix_storage_reservation_workspace  (cost=0.00..10.53 rows=34 width=0) (actual time=0.031..0.032 rows=61 loops=1)
                                                              Index Cond: (workspace_id = ws_3.id)
                                                              Buffers: shared hit=2
                    ->  Sort  (cost=1.02..1.02 rows=1 width=32) (actual time=0.054..0.055 rows=1 loops=1)
                          Sort Key: l.workspace_id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.009..0.009 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=242.31..267.06 rows=10 width=498) (actual time=1.200..1.336 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=267
        ->  Seq Scan on inbox_storage s  (cost=0.00..22.75 rows=475 width=32) (actual time=0.010..0.103 rows=422 loops=1)
              Buffers: shared hit=18
        ->  Hash  (cost=242.18..242.18 rows=10 width=136) (actual time=1.114..1.119 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=249
              ->  Hash Right Join  (cost=221.48..242.18 rows=10 width=136) (actual time=0.955..1.098 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=249
                    ->  Seq Scan on inbox x  (cost=0.00..18.80 rows=480 width=32) (actual time=0.010..0.092 rows=422 loops=1)
                          Buffers: shared hit=14
                    ->  Hash  (cost=221.36..221.36 rows=10 width=120) (actual time=0.911..0.915 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=235
                          ->  Merge Left Join  (cost=219.89..221.36 rows=10 width=120) (actual time=0.855..0.873 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=235
                                ->  Sort  (cost=59.55..59.57 rows=10 width=80) (actual time=0.367..0.369 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=45
                                      ->  Hash Right Join  (cost=57.53..59.38 rows=10 width=80) (actual time=0.326..0.338 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=45
                                            ->  HashAggregate  (cost=57.20..58.12 rows=61 width=80) (actual time=0.226..0.232 rows=10 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=45
                                                  ->  Nested Loop  (cost=0.50..56.75 rows=61 width=32) (actual time=0.064..0.202 rows=48 loops=1)
                                                        Buffers: shared hit=45
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.011 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..5.58 rows=6 width=32) (actual time=0.011..0.018 rows=5 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 48
                                                              Buffers: shared hit=45
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.056..0.057 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.023..0.028 rows=10 loops=1)
                                ->  GroupAggregate  (cost=160.35..161.22 rows=35 width=56) (actual time=0.482..0.494 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=190
                                      ->  Sort  (cost=160.35..160.43 rows=35 width=179) (actual time=0.471..0.475 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=190
                                            ->  Nested Loop  (cost=4.53..159.45 rows=35 width=179) (actual time=0.059..0.408 rows=31 loops=1)
                                                  Buffers: shared hit=190
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.011..0.014 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.88 rows=3 width=179) (actual time=0.015..0.036 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=170
                                                        Buffers: shared hit=190
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.008..0.008 rows=18 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.185 ms
Execution Time: 19.700 ms
```

## chosen-ws31-c100-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 209, relallvisible 117, reltuples 1096
storage_reservation: relpages 1169, relallvisible 1145, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=2932.90..4675.86 rows=21 width=498) (actual time=10.640..13.830 rows=12 loops=1)
  Buffers: shared hit=3308
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.021..0.023 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.012..0.014 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.013..0.015 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.014..0.034 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.014..0.015 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.016..0.018 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.015..0.015 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=225.44..225.45 rows=1 width=64) (actual time=0.904..0.904 rows=1 loops=1)
          Buffers: shared hit=209
          ->  Seq Scan on storage_delta  (cost=0.00..219.96 rows=1096 width=16) (actual time=0.021..0.730 rows=2276 loops=1)
                Buffers: shared hit=209
  CTE g_reserved
    ->  Aggregate  (cost=1186.50..1186.51 rows=1 width=40) (actual time=2.530..2.532 rows=1 loops=1)
          Buffers: shared hit=1169
          ->  Seq Scan on storage_reservation  (cost=0.00..1179.00 rows=1000 width=156) (actual time=0.005..2.249 rows=1000 loops=1)
                Buffers: shared hit=1169
  CTE fp_newest
    ->  Limit  (cost=1.04..1.05 rows=1 width=112) (actual time=0.099..0.101 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.04..1.05 rows=3 width=112) (actual time=0.097..0.098 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.03 rows=3 width=112) (actual time=0.013..0.015 rows=3 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.012..0.029 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.010..0.014 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.008..0.008 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=16.85..16.86 rows=1 width=128) (actual time=0.450..0.456 rows=1 loops=1)
          Buffers: shared hit=12
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.101..0.101 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..15.71 rows=111 width=32) (actual time=0.259..0.416 rows=372 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=12
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..13.98 rows=111 width=32) (actual time=0.123..0.212 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=10
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.087..0.089 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.060..0.065 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.014..0.041 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=14.07..14.09 rows=1 width=64) (actual time=0.181..0.188 rows=1 loops=1)
          Buffers: shared hit=8
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..14.02 rows=1 width=16) (actual time=0.171..0.175 rows=0 loops=1)
                Buffers: shared hit=8
                ->  Hash Anti Join  (cost=1.01..13.70 rows=1 width=24) (actual time=0.170..0.172 rows=0 loops=1)
                      Hash Cond: (c.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=8
                      ->  Seq Scan on workspace_deletion_charge c  (cost=0.00..11.41 rows=341 width=40) (actual time=0.008..0.057 rows=372 loops=1)
                            Buffers: shared hit=8
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.020..0.021 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.15..0.32 rows=1 width=8) (never executed)
                      Index Cond: (id = c.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.017..0.034 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.905..0.906 rows=1 loops=1)
          Buffers: shared hit=209
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=2.535..2.537 rows=1 loops=1)
          Buffers: shared hit=1169
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.453..0.454 rows=1 loops=1)
          Buffers: shared hit=12
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.008..0.009 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.009..0.010 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.017..0.017 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.007 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.008..0.008 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.095..0.098 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.092..0.094 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.091..0.093 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.010..0.013 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.029..0.030 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.103..0.108 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.054..0.056 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.025..0.025 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.012..0.013 rows=2 loops=2)
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
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.009..0.031 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.183..0.184 rows=1 loops=1)
          Buffers: shared hit=8
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=1469.10..1469.11 rows=1 width=32) (actual time=6.004..6.020 rows=1 loops=1)
          Buffers: shared hit=1387
          ->  Hash Left Join  (cost=1467.82..1468.63 rows=31 width=217) (actual time=5.258..5.336 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=1387
                ->  Merge Left Join  (cost=262.98..263.70 rows=31 width=177) (actual time=2.181..2.231 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_1.workspace_id)
                      Buffers: shared hit=218
                      ->  Merge Left Join  (cost=248.98..249.60 rows=31 width=113) (actual time=2.013..2.052 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                            Buffers: shared hit=210
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.155..0.164 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.087..0.102 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.038..0.040 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.012..0.017 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=245.80..245.88 rows=31 width=80) (actual time=1.851..1.859 rows=31 loops=1)
                                  Sort Key: d_5.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=209
                                  ->  Subquery Scan on d_5  (cost=244.26..245.03 rows=31 width=80) (actual time=1.791..1.808 rows=31 loops=1)
                                        Buffers: shared hit=209
                                        ->  HashAggregate  (cost=244.26..244.72 rows=31 width=80) (actual time=1.789..1.802 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=209
                                              ->  Hash Semi Join  (cost=1.01..236.04 rows=1096 width=32) (actual time=0.084..1.401 rows=2276 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=209
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..219.96 rows=1096 width=32) (actual time=0.022..0.914 rows=2276 loops=1)
                                                          Buffers: shared hit=209
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.031..0.032 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.006 rows=31 loops=1)
                      ->  Sort  (cost=14.00..14.01 rows=1 width=80) (actual time=0.164..0.166 rows=1 loops=1)
                            Sort Key: c_1.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=8
                            ->  Subquery Scan on c_1  (cost=13.97..13.99 rows=1 width=80) (actual time=0.116..0.118 rows=1 loops=1)
                                  Buffers: shared hit=8
                                  ->  HashAggregate  (cost=13.97..13.98 rows=1 width=80) (actual time=0.115..0.116 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=8
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..11.41 rows=341 width=32) (actual time=0.007..0.041 rows=372 loops=1)
                                              Buffers: shared hit=8
                ->  Hash  (cost=1204.45..1204.45 rows=31 width=56) (actual time=3.033..3.038 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=1169
                      ->  Subquery Scan on r_3  (cost=1203.76..1204.45 rows=31 width=56) (actual time=2.967..2.985 rows=31 loops=1)
                            Buffers: shared hit=1169
                            ->  HashAggregate  (cost=1203.76..1204.14 rows=31 width=56) (actual time=2.966..2.980 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=1169
                                  ->  Hash Semi Join  (cost=1.01..1193.76 rows=1000 width=172) (actual time=0.054..2.726 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=1169
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..1179.00 rows=1000 width=172) (actual time=0.005..2.338 rows=1000 loops=1)
                                              Buffers: shared hit=1169
                                        ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.020..0.022 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=10.639..10.694 rows=1 loops=1)
        Buffers: shared hit=2800
  ->  Hash Left Join  (cost=1383.69..1415.49 rows=10 width=498) (actual time=2.037..2.052 rows=1 loops=1)
        Hash Cond: (ws.id = l.workspace_id)
        Buffers: shared hit=266
        ->  Merge Left Join  (cost=1382.67..1414.30 rows=10 width=217) (actual time=1.975..1.990 rows=1 loops=1)
              Merge Cond: (ws.id = workspace_deletion_charge.workspace_id)
              Buffers: shared hit=265
              ->  Merge Left Join  (cost=1382.16..1382.74 rows=10 width=153) (actual time=1.815..1.827 rows=1 loops=1)
                    Merge Cond: (ws.id = r.id)
                    Buffers: shared hit=256
                    ->  Merge Left Join  (cost=241.74..242.04 rows=10 width=113) (actual time=1.611..1.621 rows=1 loops=1)
                          Merge Cond: (ws.id = d.id)
                          Buffers: shared hit=215
                          ->  Sort  (cost=10.44..10.46 rows=10 width=49) (actual time=0.498..0.505 rows=1 loops=1)
                                Sort Key: ws.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=6
                                ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.419..0.459 rows=1 loops=1)
                                      Hash Cond: (p_1.workspace_id = ws.id)
                                      Buffers: shared hit=6
                                      ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.174..0.204 rows=30 loops=1)
                                            Hash Cond: (a_1.account_id = h.id)
                                            Buffers: shared hit=4
                                            InitPlan 51 (returns $53)
                                              ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
                                                    Buffers: shared hit=1
                                            ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.092..0.110 rows=30 loops=1)
                                                  Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.004..0.007 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.052..0.052 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.009..0.014 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.030..0.031 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.008..0.012 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.222..0.225 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=2
                                            ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.152..0.162 rows=1 loops=1)
                                                  Hash Cond: (a.workspace_id = ws.id)
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.010..0.014 rows=31 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.122..0.123 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.098..0.109 rows=1 loops=1)
                                                              Hash Cond: (p.workspace_id = ws.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.019..0.022 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.038..0.039 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.025..0.026 rows=1 loops=1)
                          ->  Sort  (cost=231.30..231.38 rows=31 width=80) (actual time=1.108..1.110 rows=1 loops=1)
                                Sort Key: d.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=209
                                ->  Subquery Scan on d  (cost=229.76..230.53 rows=31 width=80) (actual time=1.079..1.081 rows=1 loops=1)
                                      Buffers: shared hit=209
                                      ->  HashAggregate  (cost=229.76..230.22 rows=31 width=80) (actual time=1.077..1.079 rows=1 loops=1)
                                            Group Key: d_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=209
                                            ->  Hash Semi Join  (cost=0.33..227.10 rows=354 width=32) (actual time=0.297..1.059 rows=44 loops=1)
                                                  Hash Cond: (d_1.workspace_id = ws_1.id)
                                                  Buffers: shared hit=209
                                                  ->  Seq Scan on storage_delta d_1  (cost=0.00..219.96 rows=1096 width=32) (actual time=0.045..0.783 rows=2276 loops=1)
                                                        Buffers: shared hit=209
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.014..0.015 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                    ->  Sort  (cost=1140.42..1140.50 rows=31 width=56) (actual time=0.200..0.202 rows=1 loops=1)
                          Sort Key: r.id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=41
                          ->  Subquery Scan on r  (cost=1138.96..1139.65 rows=31 width=56) (actual time=0.159..0.162 rows=1 loops=1)
                                Buffers: shared hit=41
                                ->  HashAggregate  (cost=1138.96..1139.34 rows=31 width=56) (actual time=0.158..0.160 rows=1 loops=1)
                                      Group Key: r_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=41
                                      ->  Nested Loop  (cost=10.75..1135.73 rows=323 width=172) (actual time=0.059..0.143 rows=31 loops=1)
                                            Buffers: shared hit=41
                                            ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.008 rows=1 loops=1)
                                                  Group Key: ws_2.id
                                                  Batches: 1  Memory Usage: 24kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                            ->  Bitmap Heap Scan on storage_reservation r_1  (cost=10.52..113.22 rows=32 width=172) (actual time=0.041..0.115 rows=31 loops=1)
                                                  Recheck Cond: (workspace_id = ws_2.id)
                                                  Heap Blocks: exact=39
                                                  Buffers: shared hit=41
                                                  ->  Bitmap Index Scan on ix_storage_reservation_workspace  (cost=0.00..10.52 rows=32 width=0) (actual time=0.025..0.026 rows=50 loops=1)
                                                        Index Cond: (workspace_id = ws_2.id)
                                                        Buffers: shared hit=2
              ->  GroupAggregate  (cost=0.51..31.51 rows=1 width=80) (actual time=0.158..0.159 rows=0 loops=1)
                    Group Key: workspace_deletion_charge.workspace_id
                    Buffers: shared hit=9
                    ->  Merge Semi Join  (cost=0.51..28.94 rows=341 width=32) (actual time=0.156..0.157 rows=0 loops=1)
                          Merge Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                          Buffers: shared hit=9
                          ->  Index Scan using ix_workspace_deletion_charge_owner on workspace_deletion_charge  (cost=0.15..24.26 rows=341 width=32) (actual time=0.039..0.099 rows=372 loops=1)
                                Buffers: shared hit=9
                          ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.023..0.024 rows=1 loops=1)
                                Sort Key: ws_3.id
                                Sort Method: quicksort  Memory: 25kB
                                ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.022..0.022 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=1
              ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.008..0.008 rows=1 loops=1)
                    Buffers: shared hit=1
  ->  Hash Right Join  (cost=300.54..327.36 rows=10 width=498) (actual time=0.870..1.003 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=242
        ->  Seq Scan on inbox x  (cost=0.00..24.79 rows=479 width=32) (actual time=0.006..0.073 rows=422 loops=1)
              Buffers: shared hit=20
        ->  Hash  (cost=300.42..300.42 rows=10 width=136) (actual time=0.826..0.830 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=222
              ->  Hash Right Join  (cost=276.01..300.42 rows=10 width=136) (actual time=0.679..0.816 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=222
                    ->  Seq Scan on inbox_storage s  (cost=0.00..22.59 rows=459 width=32) (actual time=0.010..0.111 rows=422 loops=1)
                          Buffers: shared hit=18
                    ->  Hash  (cost=275.88..275.88 rows=10 width=120) (actual time=0.631..0.635 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=204
                          ->  Merge Left Join  (cost=274.50..275.88 rows=10 width=120) (actual time=0.592..0.614 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=204
                                ->  Sort  (cost=114.24..114.26 rows=10 width=80) (actual time=0.230..0.232 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=43
                                      ->  Hash Right Join  (cost=112.79..114.07 rows=10 width=80) (actual time=0.186..0.197 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=43
                                            ->  HashAggregate  (cost=112.47..113.08 rows=41 width=80) (actual time=0.125..0.129 rows=9 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=43
                                                  ->  Nested Loop  (cost=0.50..112.16 rows=41 width=32) (actual time=0.041..0.110 rows=44 loops=1)
                                                        Buffers: shared hit=43
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.009 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..11.14 rows=4 width=32) (actual time=0.008..0.009 rows=4 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 44
                                                              Buffers: shared hit=43
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.030..0.030 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.015..0.019 rows=10 loops=1)
                                ->  GroupAggregate  (cost=160.26..161.08 rows=33 width=56) (actual time=0.357..0.371 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=161
                                      ->  Sort  (cost=160.26..160.34 rows=33 width=172) (actual time=0.349..0.352 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=161
                                            ->  Nested Loop  (cost=4.52..159.42 rows=33 width=172) (actual time=0.036..0.286 rows=31 loops=1)
                                                  Buffers: shared hit=161
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.012 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.88 rows=3 width=172) (actual time=0.010..0.025 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=141
                                                        Buffers: shared hit=161
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.007..0.007 rows=14 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.123 ms
Execution Time: 16.586 ms
```
