#!/bin/bash
# WS-B3: gate D run 3 (app request #124 comment 6677644710, route D + A, R=31): candidate #137 head 6347627, no variant, no probes.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/run3
cd $B
echo "gate run start $(date -u +%FT%TZ)"
time DBNAME=testinbox_bench_gate NAME=gate ./run.sh --workspaces 200,10000 --expected-rate 31 --rates 31,62 --host-class "staging-host vmi2932906 (Contabo VPS, 8 vCPU, 23 GiB)"
echo "rc=$? end $(date -u +%FT%TZ)"
