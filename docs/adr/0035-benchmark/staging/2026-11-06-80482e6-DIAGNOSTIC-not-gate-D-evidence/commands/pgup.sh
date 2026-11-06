#!/bin/bash
# WS-B3 DIAGNOSTIC: disposable database, same image, max_connections=300 PLUS the diagnostic-only settings of #124 6685467213
# (that is why this run is never gate evidence). No memory limit. Container log kept whole (per-container log-opt, one 2 GB file).
set -e
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/diag
docker network create --internal ws-b3-net
docker volume create ws-b3-pgdata
docker run -d --name ws-b3-pg --network ws-b3-net --env-file $B/secret/pg.env -v ws-b3-pgdata:/var/lib/postgresql/data \
  --log-opt max-size=2g --log-opt max-file=1 \
  postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685 \
  -c max_connections=300 \
  -c shared_preload_libraries=pg_stat_statements,auto_explain \
  -c pg_stat_statements.track=all -c pg_stat_statements.track_planning=on \
  -c auto_explain.log_min_duration=40ms -c auto_explain.log_analyze=on -c auto_explain.log_buffers=on \
  -c auto_explain.log_timing=on -c auto_explain.log_nested_statements=on \
  -c track_io_timing=on -c log_checkpoints=on -c log_autovacuum_min_duration=0 \
  -c log_lock_waits=on -c deadlock_timeout=50ms \
  -c log_min_duration_statement=40ms -c 'log_line_prefix=%m [%p] %a '
