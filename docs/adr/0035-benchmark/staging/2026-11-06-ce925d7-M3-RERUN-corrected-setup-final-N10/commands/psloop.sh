#!/bin/bash
# WS-B3 M3 rerun: one ps + docker ps snapshot every 60 s (additional evidence; changes no input) until loadlog.stop exists.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/m3-rerun
while [ ! -e $B/loadlog.stop ]; do
  { echo "=== $(date -u +%FT%TZ)"; ps -eo pid,ppid,user,pcpu,etime,args --sort=-pcpu | head -25 | cut -c1-220; echo "--- docker ps"; docker ps --format '{{.Names}} {{.Image}} {{.Status}}'; } >> $B/logs/ps-snapshots.log
  sleep 60
done
