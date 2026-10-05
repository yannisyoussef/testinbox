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

# --- the REAL floors, against this repository's own history (TI-STORAGE-003d) ---
# The ADR-035 floors are ancestry: a guarded-ingest artifact older than the
# final safety floor must be refused even though it contains the first one.
# These need full history (fetch-depth: 0), which every job running this has.
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
REAL_FLOORS="$REPO/deploy/rollback-floors.txt"
FIRST_GUARDED=c84ddd74f7983dba053c7d377a10960c0c409248   # first live guarded-ingest commit (floor 1)
SAFETY_FLOOR=d4e38b230af5576512ed09da6f52c44a71c266b4    # final TI-STORAGE-003 safety floor (floor 2)
PRE_EPISODE=597abf7f1ac6537d84be5673ac564139b10e3a87     # guarded, but before the durable clock episode

real() {
  local name="$1" expected="$2" candidate="$3" names="${4:-}"
  local status
  ROLLBACK_FLOORS="$REAL_FLOORS" ROLLBACK_FLOORS_REPO="$REPO" "$CHECK" --candidate "$candidate" >"$WORK/out" 2>"$WORK/err"
  status=$?
  if [[ "$status" != "$expected" ]]; then
    echo "FAIL — $name (expected exit $expected, got $status)"; sed 's/^/       /' "$WORK/err"; fail=$((fail + 1)); return
  fi
  if [[ -n "$names" ]] && ! grep -q "predates rollback floor $names" "$WORK/err"; then
    echo "FAIL — $name (the refusal does not name floor $names)"; sed 's/^/       /' "$WORK/err"; fail=$((fail + 1)); return
  fi
  echo "ok   — $name"; pass=$((pass + 1))
}

for floor in "$FIRST_GUARDED" "$SAFETY_FLOOR"; do
  grep -q "^$floor " "$REAL_FLOORS" && { echo "ok   — floor $floor is in deploy/rollback-floors.txt"; pass=$((pass + 1)); } \
    || { echo "FAIL — floor $floor is missing from deploy/rollback-floors.txt (a floor is never removed)"; fail=$((fail + 1)); }
done
real "A: a candidate before the first guarded-ingest commit is refused" 1 "$(git -C "$REPO" rev-parse "$FIRST_GUARDED^")" "$FIRST_GUARDED"
real "B: the first guarded-ingest commit itself is refused: it lacks the safety floor" 1 "$FIRST_GUARDED" "$SAFETY_FLOOR"
real "C: 597abf7 is refused: it predates the durable clock episode" 1 "$PRE_EPISODE" "$SAFETY_FLOOR"
real "D: the safety floor itself is allowed" 0 "$SAFETY_FLOOR"
real "E: this checkout's head is allowed" 0 "$(git -C "$REPO" rev-parse HEAD)"

echo "----"
echo "check-rollback-floors.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
