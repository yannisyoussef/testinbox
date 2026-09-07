#!/usr/bin/env bash
# Tests for check-migration-safety.sh.
#
# This gate exists to stop a rollback-breaking migration reaching staging, so
# what it must do is fail. The false-positive cases matter just as much: a gate
# that flags ordinary additive SQL gets bypassed within a week, and a bypassed
# gate is worse than none because it still looks like protection.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="$SCRIPT_DIR/check-migration-safety.sh"

pass=0
fail=0
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# Runs the gate over a directory containing exactly one migration.
check() {
  local name="$1" expected="$2" sql="$3"
  local dir="$WORK/case-$((pass + fail))"
  mkdir -p "$dir"
  printf '%s\n' "$sql" > "$dir/V9__case.sql"
  local output status
  output=$("$GATE" "$dir" 2>&1)
  status=$?
  if [[ "$status" == "$expected" ]]; then
    echo "ok   — $name"
    pass=$((pass + 1))
  else
    echo "FAIL — $name (expected exit $expected, got $status)"
    echo "$output" | sed 's/^/       /'
    fail=$((fail + 1))
  fi
}

SAFE=0
BLOCKED=1
DECLARED=3

# --- safe: must pass, or the gate gets bypassed ------------------------------
check "a new table passes" $SAFE \
  'CREATE TABLE widget (id uuid PRIMARY KEY, name text NOT NULL, created_at timestamptz NOT NULL);'

check "a nullable added column passes" $SAFE \
  'ALTER TABLE inbox ADD COLUMN label text;'

check "an added NOT NULL column WITH a default passes" $SAFE \
  "ALTER TABLE inbox ADD COLUMN kind text NOT NULL DEFAULT 'GENERATED';"

check "a new index passes" $SAFE \
  'CREATE INDEX ix_message_received ON message (received_at);'

check "dropping an index passes — it loosens, it does not break older code" $SAFE \
  'DROP INDEX ux_message_provider_event;'

check "the real ADR-026 migration shape passes" $SAFE \
  'DROP INDEX ux_message_provider_event;
   CREATE UNIQUE INDEX ux_message_provider_delivery
     ON message (provider, provider_message_id, envelope_to)
     WHERE provider_message_id IS NOT NULL;'

# --- false-positive resistance ----------------------------------------------
check "a line comment mentioning DROP TABLE does not trip the gate" $SAFE \
  '-- We deliberately do NOT DROP TABLE message here; see ADR-029 expand-only.
   CREATE TABLE note (id uuid PRIMARY KEY);'

check "a block comment mentioning DROP COLUMN does not trip the gate" $SAFE \
  '/* A later release will DROP COLUMN legacy_flag once nothing reads it.
      RENAME COLUMN is likewise deferred. */
   ALTER TABLE inbox ADD COLUMN successor_id uuid;'

check "NOT NULL inside CREATE TABLE does not read as an added NOT NULL column" $SAFE \
  'CREATE TABLE audit (id uuid PRIMARY KEY, actor text NOT NULL, at timestamptz NOT NULL);'

# --- blocked: rollback-breaking, undeclared ---------------------------------
check "DROP TABLE is blocked" $BLOCKED 'DROP TABLE message;'
check "DROP COLUMN is blocked" $BLOCKED 'ALTER TABLE message DROP COLUMN subject;'
check "RENAME TABLE is blocked" $BLOCKED 'ALTER TABLE inbox RENAME TO mailbox;'
check "RENAME COLUMN is blocked" $BLOCKED 'ALTER TABLE inbox RENAME COLUMN address TO email;'
check "a column type change is blocked" $BLOCKED 'ALTER TABLE message ALTER COLUMN raw_size_bytes TYPE integer;'
check "SET NOT NULL on an existing column is blocked" $BLOCKED 'ALTER TABLE message ALTER COLUMN subject SET NOT NULL;'
check "an added NOT NULL column with no default is blocked" $BLOCKED 'ALTER TABLE inbox ADD COLUMN tier text NOT NULL;'

check "case and spacing do not evade the gate" $BLOCKED \
  'alter    table   message
     drop     column    subject;'

check "one unsafe statement among safe ones is still blocked" $BLOCKED \
  'CREATE INDEX ix_a ON message (received_at);
   ALTER TABLE message DROP COLUMN subject;
   CREATE INDEX ix_b ON message (provider);'

# --- the short forms PostgreSQL also accepts --------------------------------
# `COLUMN` is optional in ALTER TABLE. Matching only the long form advertised
# coverage the gate did not have.
check "DROP without the optional COLUMN keyword is blocked" $BLOCKED \
  'ALTER TABLE message DROP subject;'
check "ALTER ... TYPE without the optional COLUMN keyword is blocked" $BLOCKED \
  'ALTER TABLE message ALTER raw_size_bytes TYPE integer;'
check "a multi-clause ADD COLUMN is judged per clause, not per statement" $BLOCKED \
  "ALTER TABLE inbox ADD COLUMN tier text NOT NULL, ADD COLUMN note text DEFAULT 'x';"

# --- destructive statements that are not ALTER TABLE ------------------------
check "DROP SCHEMA is blocked" $BLOCKED 'DROP SCHEMA public CASCADE;'
check "TRUNCATE is blocked" $BLOCKED 'TRUNCATE TABLE message;'
check "DROP TYPE is blocked" $BLOCKED 'DROP TYPE parse_status;'
check "DROP VIEW is blocked" $BLOCKED 'DROP VIEW message_summary;'

# --- loosening ALTERs must still pass, or the gate gets bypassed ------------
check "DROP CONSTRAINT passes — it loosens" $SAFE \
  'ALTER TABLE message DROP CONSTRAINT ck_message_parse_status;'
check "DROP DEFAULT passes" $SAFE 'ALTER TABLE inbox ALTER COLUMN state DROP DEFAULT;'
check "DROP NOT NULL passes — it widens what may be written" $SAFE \
  'ALTER TABLE message ALTER COLUMN subject DROP NOT NULL;'

# --- declared: visible, and handled by release policy rather than silently ok -
check "a declared destructive migration is reported as declared, not blocked" $DECLARED \
  '-- testinbox:rollback-unsafe: legacy_flag is unread since v0.4; contract release only.
   ALTER TABLE inbox DROP COLUMN legacy_flag;'

# A declaration is per FILE, not per directory. Set up a declared migration...
check "a declared migration alone reports declared" $DECLARED \
  '-- testinbox:rollback-unsafe: declared here
   ALTER TABLE inbox DROP COLUMN a;'
# ...then add an UNDECLARED one beside it: the directory must block.
last_dir="$WORK/case-$((pass + fail - 1))"
printf '%s\n' 'ALTER TABLE message DROP COLUMN subject;' > "$last_dir/V10__undeclared.sql"
status=$("$GATE" "$last_dir" >/dev/null 2>&1; echo $?)
if [[ "$status" == "$BLOCKED" ]]; then
  echo "ok   — an undeclared file alongside a declared one still blocks"
  pass=$((pass + 1))
else
  echo "FAIL — an undeclared file alongside a declared one exited $status, expected $BLOCKED"
  fail=$((fail + 1))
fi

# --- the gate itself ---------------------------------------------------------
# A blocking gate that scans nothing must fail, not report success — the
# failure mode `verify-test-results.sh` exists to prevent one layer up.
empty_dir="$WORK/no-migrations"
mkdir -p "$empty_dir"
"$GATE" "$empty_dir" >/dev/null 2>&1
if [[ $? == 2 ]]; then
  echo "ok   — an empty migrations directory is an error, not a pass"
  pass=$((pass + 1))
else
  echo "FAIL — an empty migrations directory did not fail the gate"
  fail=$((fail + 1))
fi

if "$GATE" "$WORK/definitely-not-here" >/dev/null 2>&1; then
  echo "FAIL — a missing migrations directory should be a usage error"
  fail=$((fail + 1))
else
  echo "ok   — a missing migrations directory is a usage error"
  pass=$((pass + 1))
fi

# The real migrations must pass, or the gate is broken on day one.
if "$GATE" "$SCRIPT_DIR/../backend/persistence/src/main/resources/db/migration" >/dev/null 2>&1; then
  echo "ok   — the repository's own migrations pass"
  pass=$((pass + 1))
else
  echo "FAIL — the repository's own migrations are flagged by the gate"
  fail=$((fail + 1))
fi

echo "----"
echo "check-migration-safety.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
