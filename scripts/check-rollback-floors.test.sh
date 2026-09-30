#!/usr/bin/env bash
# Self-test for scripts/check-rollback-floors.sh (ADR-034; ADR-035 §14 (e),
# §18 gate 5), the one rollback-floor check behind both the production gate
# and staging's deploy.sh. A throwaway repository gives it real ancestry.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK="$SCRIPT_DIR/check-rollback-floors.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
pass=0
fail=0

# base -> floor -> after
git -C "$WORK" init -q repo
commit() { git -C "$WORK/repo" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "$1"; git -C "$WORK/repo" rev-parse HEAD; }
BASE=$(commit base)
FLOOR=$(commit floor)
AFTER=$(commit after)
echo "$FLOOR guarded ingest protocol (fixture)" > "$WORK/floors.txt"

check() {
  local name="$1" expected="$2"; shift 2
  local status
  (cd "$WORK/repo" && ROLLBACK_FLOORS="${FLOORS_FILE:-$WORK/floors.txt}" "$CHECK" "$@") >"$WORK/out" 2>"$WORK/err"
  status=$?
  if [[ "$status" == "$expected" ]]; then
    echo "ok   — $name"; pass=$((pass + 1))
  else
    echo "FAIL — $name (expected exit $expected, got $status)"; sed 's/^/       /' "$WORK/err"; fail=$((fail + 1))
  fi
}

check "a candidate after the floor is allowed" 0 --candidate "$AFTER"
check "a candidate exactly at the floor is allowed (boundary)" 0 --candidate "$FLOOR"
check "a candidate before the floor is refused" 1 --candidate "$BASE"
grep -q "predates rollback floor $FLOOR" "$WORK/err" && { echo "ok   — the refusal names the floor and why"; pass=$((pass + 1)); } \
  || { echo "FAIL — the refusal does not name the floor"; fail=$((fail + 1)); }
check "a candidate before the floor passes only with explicit acknowledgement" 0 --candidate "$BASE" --acknowledge-rollback-hazard
grep -q "WARNING: candidate predates rollback floor" "$WORK/err" && { echo "ok   — the acknowledged hazard is still announced"; pass=$((pass + 1)); } \
  || { echo "FAIL — an acknowledged hazard passed silently"; fail=$((fail + 1)); }

printf 'not-a-sha reason\n' > "$WORK/bad.txt"
FLOORS_FILE="$WORK/bad.txt" check "a malformed floor entry refuses rather than being skipped" 1 --candidate "$AFTER"
FLOORS_FILE="$WORK/missing.txt" check "a missing floors file refuses rather than meaning 'no floors'" 1 --candidate "$AFTER"
printf '%s from another history\n' "$(printf 'e%.0s' {1..40})" > "$WORK/foreign.txt"
FLOORS_FILE="$WORK/foreign.txt" check "a floor absent from the checkout refuses (shallow clone)" 1 --candidate "$AFTER"
check "a candidate absent from the checkout refuses" 1 --candidate "$(printf 'd%.0s' {1..40})"
check "a candidate that is not a SHA refuses" 1 --candidate "main"
printf '# comments and blank lines are not floors\n\n' > "$WORK/empty.txt"
FLOORS_FILE="$WORK/empty.txt" check "a floors file with no entries allows (it was read)" 0 --candidate "$BASE"
check "no arguments is a usage error" 2 --bogus

echo "----"
echo "check-rollback-floors.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
