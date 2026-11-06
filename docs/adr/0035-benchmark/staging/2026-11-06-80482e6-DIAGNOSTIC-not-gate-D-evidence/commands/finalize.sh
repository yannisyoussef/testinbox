#!/bin/bash
# WS-B3 DIAGNOSTIC: post-run: stop recorded sampler sessions, keep the full PG server log, copy evidence + gc.log, pg settings.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/diag
cd $B
sleep 20   # let popwatch take the after-run-end dump
touch loadlog.stop
for p in $(awk '/^(vmstat|mpstat|pgsample|conncount|popwatch) pid/{print $3}' logs/pids.txt); do ps -p $p -o pid=,cmd= > logs/.ps.tmp && pkill -s $p && echo "stopped session $p ($(cat logs/.ps.tmp)) $(date -u +%FT%TZ)" >> logs/pids.txt; done
sleep 35
OUT=$(ls -d out/*/ | sort | tail -1); echo "evidence dir $OUT"
cp -a "$OUT" evidence/; cp -p out/gc.log evidence/gc.log
docker logs -t ws-b3-pg > evidence/postgres-server.log 2>&1
docker exec ws-b3-pg psql -U bench -d postgres -Atc "select version()" > evidence/pg-settings.txt
docker exec ws-b3-pg psql -U bench -d postgres -Atc "select name||' = '||setting||coalesce(' '||unit,'')||' ('||source||')' from pg_settings where source not in ('default','override') or name in ('shared_buffers','work_mem','synchronous_commit','fsync','wal_sync_method','jit','max_connections','shared_preload_libraries','autovacuum','max_wal_size','checkpoint_timeout') order by 1" >> evidence/pg-settings.txt
cp -a dumps evidence/pg-stat-dumps
