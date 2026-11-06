#!/bin/bash
# WS-B3 run 2: start samplers and the gate run, each as its own session (setsid in a non-interactive script execs in place,
# so $! is the real session leader). PIDs go to logs/pids.txt; stop ONLY those.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/run3
cd $B; rm -f loadlog.stop
st() { local name=$1 out=$2; shift 2; setsid "$@" > "$out" 2>&1 & echo "$name pid $! started $(date -u +%FT%TZ)" >> logs/pids.txt; }
st loadlog logs/loadlog.nohup ./loadlog.sh
st vmstat logs/vmstat.log env TZ=UTC LC_ALL=C vmstat -t -w -n 5
st mpstat logs/mpstat.log env TZ=UTC LC_ALL=C S_TIME_FORMAT=ISO mpstat 5
st dstats logs/dstats.nohup ./dstats.sh
st pgsample logs/pgsample.log ./pgsample.sh
st conncount logs/conncount.log ./conncount.sh
st gate.sh logs/gate.log ./gate.sh
