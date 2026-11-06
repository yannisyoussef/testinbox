#!/bin/bash
# WS-B3: NON-GATE A' FOCUSED: not gate D evidence (#124 6687011418). 8 runs in the requested order; separate DB and out dir per run.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/aprime
JDK=eclipse-temurin@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea
BASE=82eeee339100d185b9d3d200d073d520b0d2cbb7; CAND=7b09bcbb0bde59cd02957aba9a15cb58e3354beb
cd $B
i=0
for MODE in --focused-cohort --focused; do for REF in $BASE $CAND $BASE $CAND; do i=$((i+1))
  mkdir -p out/run$i
  echo "run $i $MODE ${REF:0:7} start $(date -u +%FT%TZ)"
  docker run --rm --name ws-b3-aprime-$i --network ws-b3-net --env-file $B/secret/run.env -e TESTINBOX_GIT_SHA=$REF \
    -u "$(id -u):$(id -g)" -v /home/yannis/work/ws-b3-bench-${REF:0:7}:/src:ro -v $B/out/run$i:/out -w /src $JDK \
    backend/benchmark/build/install/storage-benchmark/bin/storage-benchmark \
    --jdbc-url jdbc:postgresql://ws-b3-pg:5432/testinbox_bench_focused_$i --username bench --create-database --out /out \
    --host-class "staging-host vmi2932906 (NON-GATE A' focused, $MODE, ${REF:0:7})" $MODE
  echo "run $i rc=$? end $(date -u +%FT%TZ)"
done; done
echo "all-done $(date -u +%FT%TZ)"
