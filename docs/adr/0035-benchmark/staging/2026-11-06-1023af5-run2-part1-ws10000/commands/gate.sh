#!/bin/bash
# WS-B3: gate D run 2 (app request #124 comment 6673356486): part 1 = ws10000 only (coordinator option b: split to avoid the 05:24Z unattended-upgrade window; app fallback in 6673356486) + --partitioned-variant + timing probes.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/run2
cd $B
echo "gate run start $(date -u +%FT%TZ)"
time DBNAME=testinbox_bench_gate NAME=gate ./run.sh --workspaces 10000 --partitioned-variant --probe-repetitions 4 --probe-duration-seconds 60 --host-class "staging-host vmi2932906 (Contabo VPS, 8 vCPU, 23 GiB)"
echo "rc=$? end $(date -u +%FT%TZ)"
