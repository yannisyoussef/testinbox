#!/bin/bash
# WS-B3: FOCUSED P ASSESSMENT: not gate D evidence. App request #124 comment 6685821689 (P paper item (e), assessment only).
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/pfocus
cd $B
echo "gate run start $(date -u +%FT%TZ)"
time DBNAME=testinbox_bench_pfocus NAME=gate ./run.sh --workspaces 10000 --expected-rate 31 --rates 62 --concurrency 10,100 --duration-seconds 60 --warmup-seconds 5 --partitioned-variant --probe-repetitions 1 --probe-duration-seconds 60 --host-class "staging-host vmi2932906 (Contabo VPS, 8 vCPU, 23 GiB) FOCUSED-P"
echo "rc=$? end $(date -u +%FT%TZ)"
