#!/bin/bash
# WS-B3 FOCUSED P: post-run steps after rc= in logs/gate.log. Stops ONLY the recorded sampler PIDs (own session leaders), copies the
# evidence, records pg settings, runs the app's gate D at the same commit. Teardown is separate.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/pfocus
W=/home/yannis/work/ws-b3-bench-80482e6
cd $B
touch loadlog.stop
for p in $(awk '/^(vmstat|mpstat|pgsample|conncount) pid/{print $3}' logs/pids.txt); do ps -p $p -o pid=,cmd= > logs/.ps.tmp && pkill -s $p && echo "stopped session $p ($(cat logs/.ps.tmp)) $(date -u +%FT%TZ)" >> logs/pids.txt; done
sleep 35
OUT=$(ls -d out/*/ | sort | tail -1); echo "evidence dir $OUT"
cp -a "$OUT" evidence/
docker exec ws-b3-pg psql -U bench -d postgres -Atc "select version()" > evidence/pg-settings.txt
docker exec ws-b3-pg psql -U bench -d postgres -Atc "select name||' = '||setting||coalesce(' '||unit,'')||' ('||source||')' from pg_settings where source not in ('default','override') or name in ('shared_buffers','work_mem','effective_cache_size','synchronous_commit','fsync','wal_sync_method','jit','jit_above_cost','max_connections','superuser_reserved_connections','shared_preload_libraries','autovacuum','autovacuum_vacuum_scale_factor','autovacuum_vacuum_cost_delay','random_page_cost','max_wal_size','checkpoint_timeout') order by 1" >> evidence/pg-settings.txt
