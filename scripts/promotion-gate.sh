#!/usr/bin/env bash
# The stable aggregate that master's branch protection requires (ADR-034,
# issue #44). It reports ONE context whatever the promotion legs are called,
# and it is only trustworthy because of what it refuses:
#
#   * a leg that failed, was cancelled, or was skipped        -> fail
#   * an expected leg that is missing from `needs` entirely   -> fail
#   * a leg present in `needs` that is not on the expected list -> fail
#     (a promotion check nobody listed is a promotion check nobody required)
#   * zero expected legs                                      -> fail
#
# Usage: NEEDS_JSON='<toJSON(needs)>' promotion-gate.sh --expect job-a,job-b
set -uo pipefail

expect=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --expect) expect="${2:-}"; shift 2 ;;
    *) echo "usage: NEEDS_JSON=... $(basename "$0") --expect <job,job,...>" >&2; exit 2 ;;
  esac
done

fail() { echo "PROMOTION GATE FAILED: $*" >&2; exit 1; }

[[ -n "${NEEDS_JSON:-}" ]] || fail "NEEDS_JSON is not set; the gate has nothing to evaluate"
printf '%s' "$NEEDS_JSON" | jq -e 'type == "object"' >/dev/null 2>&1 || fail "NEEDS_JSON is not a JSON object"

# bash 3.2 (macOS) treats an empty array expansion under `set -u` as unbound;
# the `${arr[@]+"${arr[@]}"}` form is the portable spelling.
IFS=',' read -r -a expected <<< "$expect"
declare -a wanted=()
for job in ${expected[@]+"${expected[@]}"}; do
  job="${job// /}"
  [[ -n "$job" ]] && wanted+=("$job")
done
(( ${#wanted[@]} > 0 )) || fail "no expected legs were listed; an aggregate over nothing proves nothing"

failed=0
for job in ${wanted[@]+"${wanted[@]}"}; do
  result="$(printf '%s' "$NEEDS_JSON" | jq -r --arg j "$job" '.[$j].result // "missing"')"
  case "$result" in
    success) echo "ok   — $job: success" ;;
    *) echo "FAIL — $job: $result" >&2; failed=1 ;;
  esac
done

# Every leg the workflow wired into `needs` must be one this gate requires.
while IFS= read -r present; do
  listed=0
  for job in "${wanted[@]}"; do [[ "$job" == "$present" ]] && listed=1; done
  if (( listed == 0 )); then
    echo "FAIL — $present is wired into the gate's needs but not on its expected list" >&2
    failed=1
  fi
done < <(printf '%s' "$NEEDS_JSON" | jq -r 'keys[]')

(( failed == 0 )) || fail "one or more promotion legs did not succeed"
echo "PROMOTION GATE PASSED: ${#wanted[@]} leg(s) succeeded"
