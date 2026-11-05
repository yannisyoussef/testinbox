#!/bin/bash
# WS-B2 post-run diagnostics on the disposable ws-b2-pg (after the gate run ended; no load). Diagnostic only.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench2
P="docker exec -i ws-b2-pg psql -U bench -d testinbox_bench_gate -X"
stats() { $P -c "select relname, n_live_tup, n_dead_tup, pg_size_pretty(pg_total_relation_size(relid)) total, pg_relation_size(relid)/8192 heap_pages, last_autovacuum from pg_stat_user_tables where relname in ('storage_reservation','storage_delta','workspace_storage_account','inbox_storage','inbox','message','attachment') order by 1"; }
read WS IB < <($P -Atc "select workspace_id, id from inbox where state='ACTIVE' limit 1" -F ' ')
# a 10-recipient event shape: 10 consecutive inboxes of one workspace
IBS=$($P -Atc "select string_agg(''''||id||'''',',') from (select id from inbox where workspace_id='$WS' limit 10) x")
mk() { { echo "EXPLAIN (ANALYZE, BUFFERS, TIMING)"; sed -e "s/:workspaces/'$WS'/" -e "s/:inboxes/$IBS/" $B/evidence/snapshot-sql.template; echo ";"; } ; }

probe() {  # $1 label: pgbench single-client 10 s probes (after the gate run, no other load from this run)
  echo "== pgbench probes ($1)"
  $P -qc "CREATE TABLE IF NOT EXISTS ws_b2_probe (id bigint)" 
  docker exec ws-b2-pg sh -c 'printf "SELECT 1;\n" > /tmp/p_sel.sql; printf "BEGIN;\nINSERT INTO ws_b2_probe VALUES (1);\nCOMMIT;\n" > /tmp/p_commit.sql'
  mk | sed '1d' | docker exec -i ws-b2-pg sh -c 'cat > /tmp/p_snap.sql'
  for f in p_sel p_commit p_snap; do echo "-- $f"; docker exec ws-b2-pg pgbench -U bench -n -c1 -T10 -f /tmp/$f.sql testinbox_bench_gate 2>&1 | grep -E "latency average|tps =|number of transactions actually"; done
  echo "-- p_snap, 10 clients concurrently (no lock): shows CPU-parallel cost"; docker exec ws-b2-pg pgbench -U bench -n -c10 -j4 -T10 -f /tmp/p_snap.sql testinbox_bench_gate 2>&1 | grep -E "latency average|tps ="
}
echo "== $(date -u +%FT%TZ) state after the run"; $P -Atc "select count(*) from workspace_storage_account" ; $P -c "select state, count(*) from storage_reservation group by 1" -c "select count(*) deltas from storage_delta"; stats
for i in 1 2 3; do echo "== EXPLAIN ANALYZE snapshot (as left by the run) #$i"; mk | $P; done
probe "as left by the run"
echo "== VACUUM (ANALYZE) storage_reservation, storage_delta, workspace_storage_account, inbox_storage"
$P -c "VACUUM (ANALYZE, VERBOSE) storage_reservation" -c "VACUUM (ANALYZE) storage_delta" -c "VACUUM (ANALYZE) workspace_storage_account" -c "VACUUM (ANALYZE) inbox_storage" 2>&1 | grep -E "INFO|pages|tuples|VACUUM" | head -20
stats
for i in 1 2 3; do echo "== EXPLAIN ANALYZE snapshot after VACUUM #$i"; mk | $P; done
probe "after VACUUM"
echo "== VACUUM FULL storage_reservation (rewrites the heap; shows the cost with no dead space)"
$P -c "VACUUM FULL storage_reservation" -c "ANALYZE storage_reservation"; stats
for i in 1 2 3; do echo "== EXPLAIN ANALYZE snapshot after VACUUM FULL #$i"; mk | $P; done
probe "after VACUUM FULL storage_reservation"
echo "== VACUUM FULL storage_delta too"; $P -c "VACUUM FULL storage_delta" -c "ANALYZE storage_delta"; stats
for i in 1 2; do echo "== EXPLAIN ANALYZE snapshot after VACUUM FULL of both #$i"; mk | $P; done
probe "after VACUUM FULL storage_reservation + storage_delta"
echo "== autovacuum settings"; $P -Atc "select name||'='||setting||coalesce(unit,'') from pg_settings where name like 'autovacuum%' order by 1"
echo "== end $(date -u +%FT%TZ)"
