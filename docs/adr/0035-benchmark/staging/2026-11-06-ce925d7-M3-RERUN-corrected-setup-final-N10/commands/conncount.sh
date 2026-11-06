#!/bin/bash
# WS-B3 SLE: the database is recreated per run, so re-attach conncount-once.sh (one psql \watch session) whenever ws-b3-pg is up.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/m3-rerun
while [ ! -e $B/loadlog.stop ]; do
  if docker exec ws-b3-pg pg_isready -U bench -q > $B/logs/.conncount-ready.tmp 2>&1; then echo "# attach $(date -u +%FT%TZ)"; $B/conncount-once.sh; echo "# detach $(date -u +%FT%TZ)"; fi
  sleep 2
done
