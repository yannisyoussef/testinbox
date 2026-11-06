#!/bin/bash
# WS-B3 DIAGNOSTIC run (not gate D evidence): run the candidate's (80482e6) ADR-035 §11 harness in a JDK 25 container against ws-b3-pg. Args pass through.
# Usage: DBNAME=<name containing bench> NAME=<container suffix> ./run.sh <harness flags>
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/diag
W=/home/yannis/work/ws-b3-bench-80482e6
exec docker run --rm --name ws-b3-${NAME:-run} --network ws-b3-net --env-file $B/secret/run.env -e JAVA_OPTS='-Xlog:gc*,safepoint:file=/out/gc.log:time,uptime,level,tags' \
  -u "$(id -u):$(id -g)" -v $W:/src:ro -v $B/out:/out -w /src \
  eclipse-temurin@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea \
  backend/benchmark/build/install/storage-benchmark/bin/storage-benchmark \
  --jdbc-url jdbc:postgresql://ws-b3-pg:5432/"$DBNAME" --username bench --create-database --out /out "$@"
