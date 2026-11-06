#!/bin/bash
# WS-B3: CPU steal for the whole run (app request): `vmstat -t -w -n 5` (st column) and `mpstat 5` (%steal), ISO timestamps (UTC),
# each in its own file. Started with setsid; stop by the recorded PIDs (logs/pids.txt).
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3
export TZ=UTC LC_ALL=C S_TIME_FORMAT=ISO
setsid vmstat -t -w -n 5 >> $B/logs/vmstat.log 2>&1 &
echo "vmstat pid $! started $(date -u +%FT%TZ)" >> $B/logs/pids.txt
setsid mpstat 5 >> $B/logs/mpstat.log 2>&1 &
echo "mpstat pid $! started $(date -u +%FT%TZ)" >> $B/logs/pids.txt
