#!/usr/bin/env bash
# Pass 3 (review follow-up): open-loop at fixed offered rates; T1, T2, retention as separate scripts;
# 5 000 stale RELEASING reservations pre-seeded (the incident state: Σreserved is scanned under the lock).
set -euo pipefail
B=$(cd "$(dirname "$0")" && pwd); C=ti-gbench4
docker rm -f $C >/dev/null 2>&1 || true
docker run -d --name $C -e POSTGRES_PASSWORD=x -e POSTGRES_DB=b --shm-size=512m -v "$B":/b postgres:16-alpine -c max_connections=300 >/dev/null
until docker exec $C pg_isready -U postgres -d b >/dev/null 2>&1; do sleep 1; done; sleep 2
psql() { docker exec -i $C psql -U postgres -d b -v ON_ERROR_STOP=1 -q "$@"; }
for rate in 1040; do for mode in a; do
  psql -f /b/schema.sql 2>/dev/null
  fn=acct_ws; [ $mode = b ] && fn=acct_ws_g
  psql -c "CREATE TRIGGER t AFTER INSERT OR DELETE ON msg FOR EACH ROW EXECUTE FUNCTION $fn();"
  psql -c "INSERT INTO msg(workspace_id, bytes) SELECT 1 + (g % 26), 50000 FROM generate_series(1,200000) g;"
  psql -c "INSERT INTO resv(workspace_id, bytes, state, deadline_at) SELECT 1 + (g % 26), 10000000, 'RELEASING', now() - interval '1 h' FROM generate_series(1,0) g; INSERT INTO resv(workspace_id, bytes, deadline_at) SELECT 1 + (g % 26), 1000000, now() + interval '1 h' FROM generate_series(1,300) g;"
  psql -c "VACUUM ANALYZE;"
  d=/b/logs3ctl/$mode-$rate; mkdir -p "$B/logs3ctl/$mode-$rate"
  # T1 and T2 weighted equally (every admitted event is committed), retention 1 in 21 events
  docker exec -w $d $C pgbench -U postgres -n -c 16 -j 8 -T 20 --rate=$((rate*41/20)) --max-tries=10 \
    -f /b/${mode}_t1.sql@20 -f /b/${mode}_t2.sql@20 -f /b/${mode}_retention.sql@1 -l --log-prefix=tx b > "$B/logs3ctl/$mode-$rate/summary.txt" 2>&1 || true
  echo "done $mode $rate"
done; done
docker rm -f $C >/dev/null
