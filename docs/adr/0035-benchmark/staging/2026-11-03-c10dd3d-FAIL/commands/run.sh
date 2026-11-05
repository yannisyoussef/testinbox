#!/bin/bash
# WS-B2 gate D: run the candidate's (c10dd3d) ADR-035 §11 harness in a JDK 25 container against ws-b2-pg. Args pass through.
# Usage: DBNAME=<name containing bench> NAME=<container suffix> ./run.sh <harness flags>
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench2
W=/home/yannis/work/ws-b2-bench-c10dd3d
exec docker run --rm --name ws-b2-${NAME:-run} --network ws-b2-net --env-file $B/secret/run.env \
  -u "$(id -u):$(id -g)" -v $W:/src:ro -v $B/out:/out -w /src \
  eclipse-temurin@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea \
  backend/benchmark/build/install/storage-benchmark/bin/storage-benchmark \
  --jdbc-url jdbc:postgresql://ws-b2-pg:5432/"$DBNAME" --username bench --create-database --out /out "$@"
