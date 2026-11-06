#!/bin/bash
# WS-B3: NON-GATE MVP: mode a at R, production pool lifetime (#124 6693538783). bbcaacd; fresh disposable PostgreSQL (pinned digest,
# only max_connections=300, --internal); no escrow flag, no --pool-max-lifetime.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/r31prod
W=/home/yannis/work/ws-b3-bench-bbcaacd
JDK=eclipse-temurin@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea
PG=postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685
cd $B
docker network create --internal ws-b3-net > logs/.net.tmp && docker volume create ws-b3-pgdata > logs/.vol.tmp
docker run -d --name ws-b3-pg --network ws-b3-net --env-file $B/secret/pg.env -v ws-b3-pgdata:/var/lib/postgresql/data $PG -c max_connections=300 > logs/.pg.tmp
until docker exec ws-b3-pg psql -U bench -d postgres -Atc "select 1" > logs/.pgready.tmp 2>&1; do sleep 1; done
echo "pg up $(date -u +%FT%TZ): command-line settings: $(docker exec ws-b3-pg psql -U bench -d postgres -Atc "select string_agg(name||'='||setting, ', ' order by name) from pg_settings where source='command line'")"
echo "run r31-prod start $(date -u +%FT%TZ)"
docker run --rm --name ws-b3-sle-r31prod --network ws-b3-net --env-file $B/secret/run.env \
  -u "$(id -u):$(id -g)" -v $W:/src:ro -v $B/out:/out -w /src $JDK \
  backend/benchmark/build/install/storage-benchmark/bin/storage-benchmark \
  --jdbc-url jdbc:postgresql://ws-b3-pg:5432/ti_sle_bench_r31_prod --username bench --create-database --out /out/r31-prod \
  --focused-cohort --rates 31 --concurrency 10,25,50,100 --duration-seconds 60 \
  --host-class "staging-host vmi2932906 (NON-GATE MVP: mode a at R, production pool lifetime)"
echo "run r31-prod rc=$? end $(date -u +%FT%TZ)"
docker exec ws-b3-pg psql -U bench -d postgres -Atc "select version()" > evidence/pg-settings.txt
docker exec ws-b3-pg psql -U bench -d postgres -Atc "select name||' = '||setting||coalesce(' '||unit,'')||' ('||source||')' from pg_settings where source not in ('default','override') or name in ('shared_buffers','synchronous_commit','fsync','max_connections','shared_preload_libraries') order by 1" >> evidence/pg-settings.txt
docker rm -f ws-b3-pg > logs/.rm.tmp && docker network rm ws-b3-net > logs/.rm.tmp && docker volume rm ws-b3-pgdata > logs/.rm.tmp
echo "pg torn down $(date -u +%FT%TZ)"
echo "all-done $(date -u +%FT%TZ)"
