#!/bin/bash
# WS-B3: docker stats of the two bench containers every 30 s until loadlog.stop exists.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/pfocus
while [ ! -e $B/loadlog.stop ]; do echo "$(date -u +%FT%TZ) $(docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}} blk={{.BlockIO}}' ws-b3-pg ws-b3-gate 2>&1 | tr '\n' ' ')" >> $B/logs/dstats.log; sleep 28; done
