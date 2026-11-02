#!/usr/bin/env bash
# Tests for check-migration-data-movement.sh (TI-DELIVERY-002, defense in depth).
#
# A lint that cannot fail is not a lint (docs/quality/strategy.md), so every
# statement form it claims to flag has a positive fixture; and a lint that
# flags ordinary migrations gets bypassed, so the negatives matter as much.
# These tests show what the lint flags. They do NOT show — nothing here can —
# that a migration which passes it never copies sensitive data: migration
# review remains the trust boundary.
#
# Fixtures: scripts/testdata/migration-data-movement/{positive,negative}/*.sql.
# Each carries its expectations in comments (which the lint ignores):
#   -- expect-exit: <code>
#   -- expect: <extended regex the lint output must match>   (repeatable)
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LINT="$SCRIPT_DIR/check-migration-data-movement.sh"
FIXTURES="$SCRIPT_DIR/testdata/migration-data-movement"
MIGRATIONS="$REPO_ROOT/backend/persistence/src/main/resources/db/migration"
RELEASED="$SCRIPT_DIR/migration-lint/released-migrations.sha256"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
EMPTY="$WORK/empty.txt"
: > "$EMPTY"

pass=0
fail=0
OUTPUT=""

# expect_run <name> <expected exit> <regex>... -- <lint arguments>
expect_run() {
  local name="$1" expected="$2"
  shift 2
  local -a patterns=()
  while [[ $# -gt 0 && "$1" != "--" ]]; do
    patterns+=("$1")
    shift
  done
  shift
  local status
  OUTPUT="$("$LINT" "$@" 2>&1)"
  status=$?
  if [[ "$status" != "$expected" ]]; then
    echo "FAIL — $name (expected exit $expected, got $status)"
    printf '%s\n' "$OUTPUT" | sed 's/^/       /'
    fail=$((fail + 1))
    return
  fi
  local pattern
  for pattern in ${patterns[@]+"${patterns[@]}"}; do
    if ! printf '%s\n' "$OUTPUT" | grep -qE -- "$pattern"; then
      echo "FAIL — $name (output does not match /$pattern/)"
      printf '%s\n' "$OUTPUT" | sed 's/^/       /'
      fail=$((fail + 1))
      return
    fi
  done
  echo "ok   — $name"
  pass=$((pass + 1))
}

# fixture_dir <file.sql> → a fresh migrations directory holding it as V23.
fixture_dir() {
  local dir="$WORK/case-$((pass + fail))"
  mkdir -p "$dir"
  cp "$1" "$dir/V23__fixture.sql"
  printf '%s\n' "$dir"
}

hash_of() {
  printf '%s\n' "$OUTPUT" | grep -oE 'statement [0-9a-f]{16}' | head -1 | cut -d' ' -f2
}

# --- every fixture, with the expectations it declares -----------------------
for fixture in "$FIXTURES"/positive/*.sql "$FIXTURES"/negative/*.sql; do
  expected="$(sed -n 's/^-- expect-exit: //p' "$fixture")"
  mapfile -t patterns < <(sed -n 's/^-- expect: //p' "$fixture")
  if [[ -z "$expected" ]]; then
    echo "FAIL — $(basename "$fixture") declares no expect-exit"
    fail=$((fail + 1))
    continue
  fi
  case "$fixture" in */positive/*) kind=positive ;; *) kind=negative ;; esac
  if [[ "$kind" == positive && "$expected" == 0 ]] || [[ "$kind" == negative && "$expected" != 0 ]]; then
    echo "FAIL — $(basename "$fixture") is filed as $kind but expects exit $expected"
    fail=$((fail + 1))
    continue
  fi
  dir="$(fixture_dir "$fixture")"
  expect_run "$kind: $(basename "$fixture" .sql)" "$expected" ${patterns[@]+"${patterns[@]}"} -- \
    --migrations "$dir" --released "$EMPTY" --allowlist "$EMPTY"
done

# Every failure, whatever the cause, ends with the not-a-proof reminder; so
# does a pass.
dir="$(fixture_dir "$FIXTURES/positive/insert-select.sql")"
expect_run "a failure prints the not-a-proof reminder" 1 'NOT prove' 'trust boundary' -- \
  --migrations "$dir" --released "$EMPTY" --allowlist "$EMPTY"
dir="$(fixture_dir "$FIXTURES/negative/benign-additive.sql")"
expect_run "a pass prints the not-a-proof reminder too" 0 'NOT prove' 'defense in depth only' -- \
  --migrations "$dir" --released "$EMPTY" --allowlist "$EMPTY"

# --- the allow-list ----------------------------------------------------------
dir="$(fixture_dir "$FIXTURES/positive/insert-select.sql")"
expect_run "(setup) the INSERT … SELECT is flagged" 1 -- --migrations "$dir" --released "$EMPTY" --allowlist "$EMPTY"
digest="$(hash_of)"
printf 'V23__fixture.sql  %s  reviewed: copies only workspace ids, never contact data (test entry)\n' "$digest" > "$WORK/allow.txt"
expect_run "a reviewed allow-list entry suppresses exactly that statement" 0 '1 reviewed allow-list entry in use' -- \
  --migrations "$dir" --released "$EMPTY" --allowlist "$WORK/allow.txt"

# Editing the statement changes its hash: the entry no longer applies, and is stale.
sed -i 's/h.contact_email/h.contact_email || '"'"'!'"'"'/' "$dir/V23__fixture.sql"
expect_run "an edited statement needs a new review (old entry stale, finding back)" 1 \
  '\[data-movement\].*human_account' '\[stale-allowlist\]' -- \
  --migrations "$dir" --released "$EMPTY" --allowlist "$WORK/allow.txt"

# Whitespace and comments are not part of the hash.
dir="$(fixture_dir "$FIXTURES/positive/insert-select.sql")"
sed -i 's/^SELECT /  -- reformatted\n  select /' "$dir/V23__fixture.sql"
expect_run "comment and whitespace edits keep the entry valid" 0 '1 reviewed allow-list entry in use' -- \
  --migrations "$dir" --released "$EMPTY" --allowlist "$WORK/allow.txt"

dir="$(fixture_dir "$FIXTURES/negative/benign-additive.sql")"
printf 'V23__fixture.sql  0123456789abcdef  an entry for a statement that is not flagged at all\n' > "$WORK/stale.txt"
expect_run "an unused allow-list entry fails (the list cannot go stale)" 1 '\[stale-allowlist\].*0123456789abcdef' -- \
  --migrations "$dir" --released "$EMPTY" --allowlist "$WORK/stale.txt"

printf 'V23__fixture.sql  0123456789abcdef  ok\n' > "$WORK/short.txt"
expect_run "an entry without a real justification is refused" 2 'justification' -- \
  --migrations "$dir" --released "$EMPTY" --allowlist "$WORK/short.txt"
printf 'V23__fixture.sql  not-a-hash  a justification long enough to pass the length check\n' > "$WORK/malformed.txt"
expect_run "a malformed entry is refused" 2 'expected' -- \
  --migrations "$dir" --released "$EMPTY" --allowlist "$WORK/malformed.txt"

dir="$(fixture_dir "$FIXTURES/positive/grant-public.sql")"
expect_run "(setup) GRANT … TO PUBLIC is flagged" 1 -- --migrations "$dir" --released "$EMPTY" --allowlist "$EMPTY"
printf 'V23__fixture.sql  %s  attempting to allow-list a grant to PUBLIC (must not work)\n' "$(hash_of)" > "$WORK/grant.txt"
expect_run "a GRANT to PUBLIC/ingestion cannot be allow-listed" 1 '\[grant\] GRANT to PUBLIC' '\[stale-allowlist\]' -- \
  --migrations "$dir" --released "$EMPTY" --allowlist "$WORK/grant.txt"

dir="$(fixture_dir "$FIXTURES/positive/grant-dynamic.sql")"
expect_run "(setup) a dynamic GRANT is flagged" 1 -- --migrations "$dir" --released "$EMPTY" --allowlist "$EMPTY"
printf 'V23__fixture.sql  %s  reviewed: the dynamic GRANT targets the API role only (test entry)\n' "$(hash_of)" > "$WORK/dyn.txt"
expect_run "a dynamic GRANT (a heuristic) can be allow-listed after review" 0 '1 reviewed allow-list entry in use' -- \
  --migrations "$dir" --released "$EMPTY" --allowlist "$WORK/dyn.txt"

# --- released migrations are immutable --------------------------------------
expect_run "the repository passes: V1–V22 released and unchanged, nothing unreleased" 0 \
  '22 released migration\(s\) unchanged' '0 reviewed allow-list entries' --
expect_run "the released list may grow but not change (base = current)" 0 -- --released-base "$RELEASED"

copy_repo_migrations() {
  local dir="$WORK/repo-$((pass + fail))"
  mkdir -p "$dir"
  cp "$MIGRATIONS"/*.sql "$dir/"
  printf '%s\n' "$dir"
}

dir="$(copy_repo_migrations)"
printf -- '-- a quiet edit\n' >> "$dir/V22__account_lifecycle_and_restore_quarantine.sql"
expect_run "an edited released migration (V22) fails" 1 'released migration V22__.* was edited' -- \
  --migrations "$dir" --allowlist "$EMPTY"

dir="$(copy_repo_migrations)"
rm "$dir/V21__cohort_storage_pool_and_cleanup_proof.sql"
expect_run "a deleted released migration fails" 1 'V21__.* is missing' -- --migrations "$dir" --allowlist "$EMPTY"

dir="$(copy_repo_migrations)"
printf 'CREATE TABLE late (id integer);\n' > "$dir/V21_1__late.sql"
expect_run "a new migration ordered before the released head fails" 1 'V21_1__late.sql is not released but its version is not after' -- \
  --migrations "$dir" --allowlist "$EMPTY"

dir="$(copy_repo_migrations)"
cp "$FIXTURES/negative/benign-additive.sql" "$dir/V23__benign.sql"
expect_run "a benign V23 on top of the real history passes" 0 '1 unreleased migration\(s\) scanned' -- \
  --migrations "$dir" --allowlist "$EMPTY"

dir="$(copy_repo_migrations)"
cp "$FIXTURES/positive/insert-select.sql" "$dir/V23__copy.sql"
expect_run "an identity-copying V23 on top of the real history fails" 1 'V23__copy.sql:5: \[data-movement\].*human_account' -- \
  --migrations "$dir" --allowlist "$EMPTY"

# Edit V22 AND re-hash its line in the same change: only the base copy of the
# list (what CI passes as --released-base) can tell.
dir="$(copy_repo_migrations)"
printf -- '-- edited and re-hashed\n' >> "$dir/V22__account_lifecycle_and_restore_quarantine.sql"
new_hash="$(sha256sum "$dir/V22__account_lifecycle_and_restore_quarantine.sql" | cut -d' ' -f1)"
sed "s/^[0-9a-f]\{64\}  V22__/$new_hash  V22__/" "$RELEASED" > "$WORK/rehashed.sha256"
expect_run "(control) a re-hashed edit passes without the base list" 0 -- \
  --migrations "$dir" --released "$WORK/rehashed.sha256" --allowlist "$EMPTY"
expect_run "a re-hashed edit fails against the base list (append-only)" 1 'V22__.* no longer says so; released lines are append-only' -- \
  --migrations "$dir" --released "$WORK/rehashed.sha256" --allowlist "$EMPTY" --released-base "$RELEASED"

# A migration this change adds AND pins is still linted against the base list;
# on a push (no base list) it counts as released, having been linted in its PR.
dir="$(copy_repo_migrations)"
cp "$FIXTURES/positive/insert-select.sql" "$dir/V23__copy.sql"
cp "$RELEASED" "$WORK/pinned.sha256"
sha256sum "$dir/V23__copy.sql" | sed 's|  .*/|  |' >> "$WORK/pinned.sha256"
expect_run "a migration pinned in the same change is still linted (base list)" 1 'V23__copy.sql:5: \[data-movement\].*human_account' -- \
  --migrations "$dir" --released "$WORK/pinned.sha256" --allowlist "$EMPTY" --released-base "$RELEASED"
expect_run "(push, no base list) a pinned migration is not re-linted" 0 '23 released migration\(s\) unchanged' -- \
  --migrations "$dir" --released "$WORK/pinned.sha256" --allowlist "$EMPTY"

# Once pinned, its reviewed allow-list entry is inert rather than stale, so the
# push after the merge stays green.
dir="$(copy_repo_migrations)"
cp "$FIXTURES/positive/insert-select.sql" "$dir/V23__copy.sql"
expect_run "(setup) the V23 copy is flagged before pinning" 1 -- --migrations "$dir" --allowlist "$EMPTY"
printf 'V23__copy.sql  %s  reviewed: copies only workspace ids, never contact data (test entry)\n' "$(hash_of)" > "$WORK/pinned-allow.txt"
expect_run "a reviewed entry passes the pull request that pins its migration" 0 '1 reviewed allow-list entry in use' -- \
  --migrations "$dir" --released "$WORK/pinned.sha256" --allowlist "$WORK/pinned-allow.txt" --released-base "$RELEASED"
expect_run "and is inert, not stale, once the migration is released" 0 '0 reviewed allow-list entries in use' -- \
  --migrations "$dir" --released "$WORK/pinned.sha256" --allowlist "$WORK/pinned-allow.txt"

printf 'nonsense\n' > "$WORK/bad-released.sha256"
expect_run "a malformed released list is a configuration error" 2 'expected' -- --released "$WORK/bad-released.sha256"
mkdir -p "$WORK/no-migrations"
expect_run "an empty migrations directory is an error, not a pass" 2 'would scan nothing' -- \
  --migrations "$WORK/no-migrations" --released "$EMPTY" --allowlist "$EMPTY"

# --- real history: what the lint WOULD flag if V1–V22 were unreleased -------
# Released migrations are not re-linted, but the real history is the best
# evidence that the patterns catch real shapes and nothing absurd. Each of
# these was reviewed when it shipped (V19/V22 data movement: workspace ids and
# booleans only, no contact, identity, session or key data).
expect_run "real history, linted as if unreleased, shows the reviewed shapes" 1 \
  'V10__.*:454: \[dynamic-sql\]' \
  'V19__.*:32: \[data-movement\] INSERT statement \(data movement\) reads beta_admission' \
  'V22__.*:23: \[data-movement\] INSERT statement \(data movement\) reads beta_admission' \
  'V22__.*:97: \[data-movement\] CREATE VIEW reads human_account' \
  'V22__.*:68: \[trigger\] trigger on/referencing human_account' \
  'V22__.*:111: \[routine-body\] FUNCTION body or signature reads api_key' \
  'FAILED: 16 finding' -- \
  --released "$EMPTY" --allowlist "$EMPTY"
if printf '%s\n' "$OUTPUT" | grep -qE '\[grant\]'; then
  echo "FAIL — real history contains a GRANT finding"
  fail=$((fail + 1))
else
  echo "ok   — real history has no GRANT to the ingestion role or PUBLIC"
  pass=$((pass + 1))
fi

echo "----"
echo "$pass passed, $fail failed"
[[ "$fail" -eq 0 ]]
