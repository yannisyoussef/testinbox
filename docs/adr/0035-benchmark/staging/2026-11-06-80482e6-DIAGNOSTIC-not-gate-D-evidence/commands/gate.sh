#!/bin/bash
# WS-B3: DIAGNOSTIC run (not gate D evidence), app request #124 comment 6685467213, owner-approved (6684712608). 8 scenarios.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/diag
cd $B
echo "gate run start $(date -u +%FT%TZ)"
time DBNAME=testinbox_bench_diag NAME=gate ./run.sh --workspaces 200,10000 --expected-rate 31 --rates 62 --concurrency 10,100 --duration-seconds 60 --warmup-seconds 5 --host-class "staging-host vmi2932906 (Contabo VPS, 8 vCPU, 23 GiB) DIAGNOSTIC"
echo "rc=$? end $(date -u +%FT%TZ)"
