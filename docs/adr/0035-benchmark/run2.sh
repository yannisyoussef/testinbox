#!/usr/bin/env bash
# Pass 2: per-statement latency (-r) to isolate the global admission lock wait; 128 clients with max_connections raised.
set -euo pipefail
B=$(cd "$(dirname "$0")" && pwd)
C=ti-gbench2
docker rm -f $C >/dev/null 2>&1 || true
docker run -d --name $C -e POSTGRES_PASSWORD=x -e POSTGRES_DB=b --shm-size=512m -v "$B":/b postgres:16-alpine -c max_connections=300 >/dev/null
until docker exec $C pg_isready -U postgres -d b >/dev/null 2>&1; do sleep 1; done; sleep 2
psql() { docker exec -i $C psql -U postgres -d b -v ON_ERROR_STOP=1 -q "$@"; }
for mode in a b; do
  for clients in 8 16 64 128; do
    psql -f /b/schema.sql 2>/dev/null
    fn=acct_ws; [ $mode = b ] && fn=acct_ws_g
    psql -c "CREATE TRIGGER t AFTER INSERT OR DELETE ON msg FOR EACH ROW EXECUTE FUNCTION $fn();"
    psql -c "INSERT INTO msg(workspace_id, bytes) SELECT 1 + (g % 26), 50000 FROM generate_series(1,200000) g;"; psql -c "VACUUM ANALYZE;"
    echo "== mode=$mode clients=$clients"
    docker exec $C pgbench -U postgres -n -c $clients -j 8 -T 20 --max-tries=10 -r \
      -f /b/${mode}_ingest.sql@20 -f /b/${mode}_retention.sql@1 b 2>&1 \
      | grep -E "tps =|failed trans|retried|pg_advisory|UPDATE g_acct|^ +[0-9.]+ +[0-9]+ +[0-9]+ +(COMMIT|SELECT 1 FROM g_acct)" || true
  done
done
docker rm -f $C >/dev/null
