#!/bin/bash
# WS-B3 A' focused: disposable database for this NON-GATE run only: max_connections=300 + pg_stat_statements with track_planning
# (#124 6687011418). --internal network, no memory limit. Password from the 0600 env file.
set -e
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/aprime
docker network create --internal ws-b3-net
docker volume create ws-b3-pgdata
docker run -d --name ws-b3-pg --network ws-b3-net --env-file $B/secret/pg.env -v ws-b3-pgdata:/var/lib/postgresql/data \
  postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685 \
  -c max_connections=300 -c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.track_planning=on
