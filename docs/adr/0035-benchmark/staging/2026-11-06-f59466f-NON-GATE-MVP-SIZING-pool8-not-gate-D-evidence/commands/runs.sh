#!/bin/bash
# WS-B3: NON-GATE MVP SIZING: not gate D evidence (#124 6693997866), pool size 8. f59466f. A FRESH disposable PostgreSQL per run
# (container ws-b3-pg, network ws-b3-net --internal, volume ws-b3-pgdata, all recreated per run); pinned digest, max_connections=300;
# only max_connections=300; no --pool-max-lifetime. DB names as requested (they contain 'bench').
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/sizing-p8
W=/home/yannis/work/ws-b3-bench-f59466f
JDK=eclipse-temurin@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea
PG=postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685
cd $B; mkdir -p out
pgup() {
  docker network create --internal ws-b3-net > logs/.net.tmp && docker volume create ws-b3-pgdata > logs/.vol.tmp
  docker run -d --name ws-b3-pg --network ws-b3-net --env-file $B/secret/pg.env -v ws-b3-pgdata:/var/lib/postgresql/data $PG \
    -c max_connections=300 "$@" > logs/.pg.tmp
  until docker exec ws-b3-pg psql -U bench -d postgres -Atc "select 1" > logs/.pgready.tmp 2>&1; do sleep 1; done
  echo "  pg up $(date -u +%FT%TZ): command-line settings: $(docker exec ws-b3-pg psql -U bench -d postgres -Atc "select string_agg(name||'='||setting, ', ' order by name) from pg_settings where source='command line'")"
}
pgdown() {
  local tag=$1
  docker exec ws-b3-pg psql -U bench -d postgres -Atc "select version()" > evidence/pg-settings-$tag.txt
  docker exec ws-b3-pg psql -U bench -d postgres -Atc "select name||' = '||setting||coalesce(' '||unit,'')||' ('||source||')' from pg_settings where source not in ('default','override') or name in ('shared_buffers','synchronous_commit','fsync','max_connections','shared_preload_libraries','pg_stat_statements.track_planning') order by 1" >> evidence/pg-settings-$tag.txt
  docker rm -f ws-b3-pg > logs/.rm.tmp && docker network rm ws-b3-net > logs/.rm.tmp && docker volume rm ws-b3-pgdata > logs/.rm.tmp
  echo "  pg torn down $(date -u +%FT%TZ)"
}
run() { # tag db [pg extra args] -- harness args
  local tag=$1 db=$2; shift 2; local pgargs=(); while [ "$1" != "--" ]; do pgargs+=("$1"); shift; done; shift
  echo "run $tag start $(date -u +%FT%TZ) db=$db harness: $*"
  pgup "${pgargs[@]}"
  docker run --rm --name ws-b3-sle-$tag --network ws-b3-net --env-file $B/secret/run.env \
    -u "$(id -u):$(id -g)" -v $W:/src:ro -v $B/out:/out -w /src $JDK \
    backend/benchmark/build/install/storage-benchmark/bin/storage-benchmark \
    --jdbc-url jdbc:postgresql://ws-b3-pg:5432/$db --username bench --create-database --out /out/$tag "$@"
  echo "run $tag rc=$? end $(date -u +%FT%TZ)"
  pgdown $tag
}
run p8-n30 ti_bench_p8_n30 -- \
  --focused-cohort --pool-size 8 --rates 31,62 \
  --concurrency 10,25,50,100 --duration-seconds 60 --host-class "staging-host vmi2932906 (NON-GATE MVP SIZING, pool 8, N=30)"
run p8-n15 ti_bench_p8_n15 -- \
  --focused-cohort --pool-size 8 --cohort-participants 15 --expected-rate 16 \
  --rates 16,32 --concurrency 10,25,50,100 --duration-seconds 60 --host-class "staging-host vmi2932906 (NON-GATE MVP SIZING, pool 8, N=15)"
if [ -e $B/RUN_N10 ]; then
run p8-n10 ti_bench_p8_n10 -- \
  --focused-cohort --pool-size 8 --cohort-participants 10 --expected-rate 11 \
  --rates 11,22 --concurrency 10,25,50,100 --duration-seconds 60 --host-class "staging-host vmi2932906 (NON-GATE MVP SIZING, pool 8, N=10)"
else echo "p8-n10 not run (RUN_N10 marker absent: run 1 did not fail at R, per Ops check) $(date -u +%FT%TZ)"; fi
echo "all-done $(date -u +%FT%TZ)"
