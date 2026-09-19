#!/usr/bin/env bash
# The backup gate must be able to fail, including on a backup that examines
# nothing — a green result on an empty dump is exactly the false comfort a
# restore drill exists to remove.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GATE="$SCRIPT_DIR/check-backup-scope.sh"
pass=0; fail=0
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

check() {
  local name="$1" expected="$2"; shift 2
  local status out
  out=$("$GATE" "$@" 2>&1); status=$?
  if [[ "$status" == "$expected" ]]; then
    echo "ok   — $name"; pass=$((pass + 1))
  else
    echo "FAIL — $name (expected exit $expected, got $status)"; echo "$out" | sed 's/^/       /' | tail -4; fail=$((fail + 1))
  fi
}

# --- the real schema is fully classified ------------------------------------
check "every table in the real migrations is classified" 0 \
  --classification "$REPO_ROOT/backend/persistence/src/main/resources/db/migration"
mkdir -p "$WORK/mig"; printf 'CREATE TABLE audit_event (id uuid);\n' > "$WORK/mig/V9__audit.sql"
check "a new, unclassified table fails the classification check" 1 --classification "$WORK/mig"
# The forms a real migration takes that a naive pattern lets drift in: quoted,
# UNLOGGED (the idiomatic choice for exactly the transient tables this scope
# denies), split across lines, and a digit suffix that merely shares a prefix
# with a classified table.
for form in 'CREATE TABLE "audit_event" (id uuid);' 'CREATE UNLOGGED TABLE audit_event (id uuid);' \
            $'create table\n  audit_event (id uuid);' 'CREATE TABLE rate_bucket2 (id uuid);' \
            'CREATE TABLE IF NOT EXISTS public.audit_event (id uuid);'; do
  printf '%s\n' "$form" > "$WORK/mig/V9__audit.sql"
  check "unclassified table in the form [${form//$'\n'/\\n}] is caught" 1 --classification "$WORK/mig"
done
printf -- '-- CREATE TABLE audit_event would be wrong here\nCREATE TABLE workspace (id uuid);\n' > "$WORK/mig/V9__audit.sql"
check "a table name inside a comment is not a table" 0 --classification "$WORK/mig"
mkdir -p "$WORK/empty"
check "an empty migrations directory fails rather than passing vacuously" 1 --classification "$WORK/empty"

# --- plain-SQL dumps ----------------------------------------------------------
control_plane_dump() {
  cat <<'SQL'
CREATE TABLE public.workspace (id uuid);
COPY public.workspace (id, name) FROM stdin;
\.
COPY public.project (id) FROM stdin;
\.
COPY public.api_key (id, key_hash) FROM stdin;
\.
COPY public.exact_address_reservation (id) FROM stdin;
\.
COPY public.flyway_schema_history (installed_rank) FROM stdin;
\.
CREATE TABLE public.message (id uuid);
CREATE TABLE public.inbox (id uuid);
SQL
}
control_plane_dump > "$WORK/good.sql"
check "a control-plane-only dump passes (content tables schema-only)" 0 "$WORK/good.sql"

{ control_plane_dump; printf 'COPY public.message (id, raw_object_key) FROM stdin;\n\\.\n'; } > "$WORK/content.sql"
check "a dump carrying message rows is refused — it would retain expired mail" 1 "$WORK/content.sql"

{ control_plane_dump; printf 'INSERT INTO public.attachment VALUES (1);\n'; } > "$WORK/insert.sql"
check "INSERT-style content data is caught as well as COPY" 1 "$WORK/insert.sql"
{ control_plane_dump; printf '\tCOPY public.message (id) FROM stdin;\n\\.\n'; } > "$WORK/tab.sql"
check "a COPY of content behind a leading tab is caught" 1 "$WORK/tab.sql"
{ control_plane_dump; printf 'INSERT INTO\n    public.message VALUES (1);\n'; } > "$WORK/multiline.sql"
check "an INSERT split across lines is caught" 1 "$WORK/multiline.sql"
{ control_plane_dump; printf 'COPY "public"."message" (id) FROM stdin;\n\\.\n'; } > "$WORK/quoted.sql"
check "a quoted, schema-qualified COPY of content is caught" 1 "$WORK/quoted.sql"

{ control_plane_dump | grep -v api_key; } > "$WORK/nokeys.sql"
check "a dump missing api_key is refused — a restore would lose every credential" 1 "$WORK/nokeys.sql"

{ control_plane_dump; printf 'COPY public.mystery_table (x) FROM stdin;\n\\.\n'; } > "$WORK/unknown.sql"
check "an unclassified table in the dump is refused" 1 "$WORK/unknown.sql"

printf 'CREATE TABLE public.workspace (id uuid);\n' > "$WORK/schema-only.sql"
check "a schema-only dump examines nothing and fails (positive control)" 1 "$WORK/schema-only.sql"
: > "$WORK/empty.sql"
check "an empty file fails" 1 "$WORK/empty.sql"

# --- pg_restore -l style table lists -----------------------------------------
printf 'workspace\nproject\napi_key\nexact_address_reservation\nflyway_schema_history\n' > "$WORK/list.txt"
check "a table list of exactly the control plane passes" 0 "$WORK/list.txt"
# The documented recipe, run against a genuine `pg_restore -l` excerpt.
cat > "$WORK/restore.list" <<'LIST'
;
; Archive created at 2026-09-19 12:00:00 UTC
;
3117; 0 16391 TABLE DATA public workspace testinbox
3118; 0 16402 TABLE DATA public project testinbox
3119; 0 16413 TABLE DATA public api_key testinbox
3120; 0 16424 TABLE DATA public exact_address_reservation testinbox
3121; 0 16380 TABLE DATA public flyway_schema_history testinbox
215; 1259 16391 TABLE public message testinbox
LIST
awk '/ TABLE DATA / {print $7}' "$WORK/restore.list" > "$WORK/from-restore.txt"
check "the documented pg_restore -l recipe yields a passing control-plane list" 0 "$WORK/from-restore.txt"
printf '3122; 0 16435 TABLE DATA public message testinbox\n' >> "$WORK/restore.list"
awk '/ TABLE DATA / {print $7}' "$WORK/restore.list" > "$WORK/from-restore-content.txt"
check "and refuses once message data appears in the archive listing" 1 "$WORK/from-restore-content.txt"
printf 'public.workspace\nPUBLIC.PROJECT\n"api_key"\nexact_address_reservation\nflyway_schema_history\n' > "$WORK/list-forms.txt"
check "schema prefixes, quoting and case in a list are normalised" 0 "$WORK/list-forms.txt"
printf 'workspace\nproject\napi_key\nexact_address_reservation\nflyway_schema_history\ninbox\n' > "$WORK/list-inbox.txt"
check "a list including inbox data is refused" 1 "$WORK/list-inbox.txt"
check "a missing input is a usage error" 2 "$WORK/nope.txt"

echo "----"
echo "check-backup-scope.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
