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
printf 'public.workspace\nPUBLIC.PROJECT\n"api_key"\nexact_address_reservation\nflyway_schema_history\n' > "$WORK/list-forms.txt"
check "schema prefixes, quoting and case in a list are normalised" 0 "$WORK/list-forms.txt"
printf 'workspace\nproject\napi_key\nexact_address_reservation\nflyway_schema_history\ninbox\n' > "$WORK/list-inbox.txt"
check "a list including inbox data is refused" 1 "$WORK/list-inbox.txt"
check "a missing input is a usage error" 2 "$WORK/nope.txt"

echo "----"
echo "check-backup-scope.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
