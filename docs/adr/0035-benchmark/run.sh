#!/usr/bin/env bash
# Global storage-accounting contention benchmark (TI-DOC-001 / ADR-035 §F). Scratch only.
set -euo pipefail
B=$(cd "$(dirname "$0")" && pwd)
C=ti-gbench
docker rm -f $C >/dev/null 2>&1 || true
docker run -d --name $C -e POSTGRES_PASSWORD=x -e POSTGRES_DB=b --shm-size=512m -v "$B":/b postgres:16-alpine >/dev/null
until docker exec $C pg_isready -U postgres -d b >/dev/null 2>&1; do sleep 1; done; sleep 2
psql() { docker exec -i $C psql -U postgres -d b -v ON_ERROR_STOP=1 -q "$@"; }
docker exec $C nproc; psql -c "select version()" -At
for mode in c a b; do
  for clients in 1 16 64 128; do
    psql -f /b/schema.sql
    case $mode in
      a|c) psql -c "CREATE TRIGGER t AFTER INSERT OR DELETE ON msg FOR EACH ROW EXECUTE FUNCTION acct_ws();" ;;
      b)   psql -c "CREATE TRIGGER t AFTER INSERT OR DELETE ON msg FOR EACH ROW EXECUTE FUNCTION acct_ws_g();" ;;
    esac
    # a standing population so retention has real work and sums are not over empty tables
    psql -c "INSERT INTO msg(workspace_id, bytes) SELECT 1 + (g % 26), 50000 FROM generate_series(1,200000) g;"; psql -c "VACUUM ANALYZE;"
    mkdir -p "$B/logs/$mode-$clients"
    ret=""; [ "$mode" != c ] && ret="-f /b/${mode}_retention.sql@1"
    [ "$mode" = c ] && ret="-f /b/a_retention.sql@1"
    docker exec -w /b/logs/$mode-$clients $C pgbench -U postgres -n -c $clients -j 8 -T 20 --max-tries=10 \
      -f /b/${mode}_ingest.sql@20 $ret -l --log-prefix=tx b > "$B/logs/$mode-$clients/summary.txt" 2>&1 || true
    echo "== mode=$mode clients=$clients"; grep -E "tps|failed|latency average|script [0-9]|- [0-9]+ transactions" "$B/logs/$mode-$clients/summary.txt" | head -20
  done
done
docker rm -f $C >/dev/null
