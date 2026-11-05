#!/bin/bash
# WS-B2: the gate run, documented population set (docs/dev/storage-benchmark.md "The gate, on the staging host class").
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench2
cd $B
echo "gate run start $(date -u +%FT%TZ)"
time DBNAME=testinbox_bench_gate NAME=gate ./run.sh --workspaces 200,10000 --host-class "staging-host vmi2932906 (Contabo VPS, 8 vCPU, 23 GiB)"
echo "rc=$? end $(date -u +%FT%TZ)"
