#!/bin/bash
# WS-B2: docker stats of the two bench containers every 30 s until loadlog.stop exists.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench2
while [ ! -e $B/loadlog.stop ]; do echo "$(date -u +%FT%TZ) $(docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}} blk={{.BlockIO}}' ws-b2-pg ws-b2-gate 2>&1 | tr '\n' ' ')" >> $B/logs/dstats.log; sleep 28; done
