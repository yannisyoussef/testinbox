#!/bin/bash
# WS-B3 SLE prototype: docker stats of ws-b3-pg and the current ws-b3-sle-* container every 30 s until loadlog.stop exists.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/sle
while [ ! -e $B/loadlog.stop ]; do echo "$(date -u +%FT%TZ) $(docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}} blk={{.BlockIO}}' $(docker ps --format '{{.Names}}' | /usr/bin/grep -e '^ws-b3-pg$' -e '^ws-b3-sle-') 2>&1 | tr '\n' ' ')" >> $B/logs/dstats.log; sleep 28; done
