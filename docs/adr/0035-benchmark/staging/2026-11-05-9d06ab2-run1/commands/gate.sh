#!/bin/bash
# WS-B3: gate D run 1 (app request #124 comment 6667610970): documented population set + --partitioned-variant.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3
cd $B
echo "gate run start $(date -u +%FT%TZ)"
time DBNAME=testinbox_bench_gate NAME=gate ./run.sh --workspaces 200,10000 --partitioned-variant --host-class "staging-host vmi2932906 (Contabo VPS, 8 vCPU, 23 GiB)"
echo "rc=$? end $(date -u +%FT%TZ)"
