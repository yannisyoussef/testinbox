#!/usr/bin/env bash
# ADR-035 §14 / TI-STORAGE-001 §21: measure the REAL V6 migration on a dataset of
# ~1 000 000 messages and ~300 000 attachments. Throwaway container; nothing persisted.
# Usage: run.sh   (from anywhere; needs docker)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
MIG="$HERE/../../../../backend/persistence/src/main/resources/db/migration"
C=ti-v6-bench
docker rm -f $C >/dev/null 2>&1 || true
# 1 GB of /dev/shm: Docker's 64 MB default is too small for parallel hash joins at this size.
docker run -d --name $C --shm-size=1g -e POSTGRES_PASSWORD=x -e POSTGRES_DB=b postgres:16-alpine >/dev/null
until docker exec $C pg_isready -U postgres -d b >/dev/null 2>&1; do sleep 1; done; sleep 2
psql() { docker exec -i $C psql -q -v ON_ERROR_STOP=1 -U postgres -d b "$@"; }
for f in V1 V2 V3 V4 V5; do psql < "$(ls "$MIG"/${f}__*.sql)"; done
echo "== seeding 50 workspaces, 5 000 inboxes, 1 000 000 messages, 300 000 attachments"
psql <<'SQL'
INSERT INTO workspace SELECT gen_random_uuid(), 'w', now() FROM generate_series(1, 50);
INSERT INTO project SELECT id, id, 'p', now() FROM workspace;
CREATE TEMP TABLE ws AS SELECT id, row_number() OVER () AS n FROM workspace;
INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state, created_at, expires_at)
SELECT gen_random_uuid(), ws.id, ws.id, 'i' || g || '@bench', 'GENERATED', 'ACTIVE', now(), now() + interval '1 day'
  FROM generate_series(1, 5000) g JOIN ws ON ws.n = 1 + (g % 50);
CREATE TEMP TABLE ib AS SELECT id, workspace_id, row_number() OVER () AS n FROM inbox;
INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, envelope_to, raw_object_key,
                     raw_size_bytes, content_fingerprint, parse_status, text_body)
SELECT gen_random_uuid(), ib.workspace_id, ib.id, now(), 'smtp', 'x', 'k', 20000 + (g % 100000), 'fp', 'OK',
       repeat('body ', 40)
  FROM generate_series(1, 1000000) g JOIN ib ON ib.n = 1 + (g % 5000);
INSERT INTO attachment (id, workspace_id, message_id, size_bytes, object_key)
SELECT gen_random_uuid(), workspace_id, id, 30000, 'k' FROM message LIMIT 300000;
SQL
psql -c "VACUUM ANALYZE;"
psql -At -c "SELECT 'dataset: messages=' || (SELECT count(*) FROM message) || ' attachments=' || (SELECT count(*) FROM attachment) || ' message_table=' || pg_size_pretty(pg_total_relation_size('message'));"
run_v6() {
  local label=$1
  # Exactly V6, in one transaction as Flyway runs it. \timing reports each statement.
  { echo '\timing on'; echo 'BEGIN;'; cat "$MIG/V6__storage_accounting_foundation.sql"; echo 'COMMIT;'; } \
    | docker exec -i $C psql -v ON_ERROR_STOP=1 -U postgres -d b 2>&1 \
    | awk -v label="$label" '/^Time:/ {t=$2; n++; if (n==2) lock=t; total+=t} END {printf "%s: statements=%d  LOCK TABLE=%s ms  total=%.0f ms\n", label, n, lock, total}'
}
echo "== warm (buffers populated by the seeding)"
run_v6 "V6 warm"
psql -At -c "SELECT 'check: ws_base_sum=' || (SELECT sum(base_bytes) FROM workspace_storage_account) || ' derived=' || ((SELECT sum(raw_size_bytes) FROM message) + (SELECT sum(size_bytes) FROM attachment)) || ' inbox_rows=' || (SELECT count(*) FROM inbox_storage) || ' deltas=' || (SELECT count(*) FROM storage_delta);"
echo "== cold (PostgreSQL restarted: shared buffers empty; the Docker VM's page cache is NOT dropped)"
psql < <(cat <<'SQL'
DROP TABLE storage_admission_latch, storage_node, storage_ambiguity, storage_reservation, storage_delta, inbox_storage, workspace_storage_account;
DROP FUNCTION storage_account_recompute(); DROP TRIGGER storage_ledger_message_insert ON message; DROP TRIGGER storage_ledger_message_delete ON message;
DROP TRIGGER storage_ledger_message_update ON message; DROP TRIGGER storage_ledger_attachment_insert ON attachment;
DROP TRIGGER storage_ledger_attachment_delete ON attachment; DROP TRIGGER storage_ledger_attachment_update ON attachment;
DROP FUNCTION storage_ledger_message(); DROP FUNCTION storage_ledger_attachment();
SQL
)
docker restart $C >/dev/null; until docker exec $C pg_isready -U postgres -d b >/dev/null 2>&1; do sleep 1; done; sleep 2
run_v6 "V6 cold"
docker exec $C nproc | xargs echo "cpus:"; docker exec $C psql -At -U postgres -c "SELECT version();"
docker rm -f $C >/dev/null
