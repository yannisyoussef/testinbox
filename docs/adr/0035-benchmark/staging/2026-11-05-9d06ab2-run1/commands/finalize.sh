#!/bin/bash
# WS-B3: post-run steps after rc= in logs/gate.log. Stops ONLY the recorded sampler PIDs (own session leaders), copies the evidence,
# records pg settings, runs the app's gate D at the same commit. Teardown is a separate step (teardown.sh).
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3
W=/home/yannis/work/ws-b3-bench-9d06ab2
cd $B
touch loadlog.stop
for p in 1214391 1214395 1214406 1214408; do ps -p $p -o pid=,cmd= > logs/.ps.tmp && kill $p && echo "stopped $p $(cat logs/.ps.tmp) $(date -u +%FT%TZ)" >> logs/pids.txt; done
sleep 35
OUT=$(ls -d out/*/ | sort | tail -1); echo "evidence dir $OUT"
cp -a "$OUT" evidence/
docker exec ws-b3-pg psql -U bench -d postgres -Atc "select version()" > evidence/pg-settings.txt
docker exec ws-b3-pg psql -U bench -d postgres -Atc "select name||' = '||setting||coalesce(' '||unit,'')||' ('||source||')' from pg_settings where source not in ('default','override') or name in ('shared_buffers','work_mem','effective_cache_size','synchronous_commit','fsync','wal_sync_method','jit','jit_above_cost','max_connections','shared_preload_libraries','autovacuum','autovacuum_vacuum_scale_factor','autovacuum_vacuum_cost_delay','random_page_cost','max_wal_size','checkpoint_timeout') order by 1" >> evidence/pg-settings.txt
EV=$B/evidence/$(basename $OUT)/evidence.json
echo "gate D at $(git -C $W rev-parse HEAD), start $(date -u +%FT%TZ)" > logs/gate-all.meta
(cd $W && scripts/check-storage-activation.sh --mode ALL --benchmark-evidence "$EV" --json $B/evidence/gate-all.json > $B/evidence/gate-all.out 2>&1; echo "rc=$?" >> $B/logs/gate-all.meta)
date -u +%FT%TZ >> logs/gate-all.meta; cat logs/gate-all.meta
