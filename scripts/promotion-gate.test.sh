#!/usr/bin/env bash
# Proves the aggregate can fail (docs/quality/strategy.md). Branch protection
# on master will require this ONE context, so every way it could go green for
# the wrong reason is asserted here.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="$SCRIPT_DIR/promotion-gate.sh"
pass=0; fail=0

check() {
  local name="$1" expected="$2" needs="$3" expect="$4"
  local status
  NEEDS_JSON="$needs" "$GATE" --expect "$expect" >/dev/null 2>&1
  status=$?
  if [[ "$status" == "$expected" ]]; then
    echo "ok   — $name"; pass=$((pass + 1))
  else
    echo "FAIL — $name (expected exit $expected, got $status)"; fail=$((fail + 1))
  fi
}

ALL_GREEN='{"candidate-identity":{"result":"success"},"vulnerability-policy":{"result":"success"},"migration-policy":{"result":"success"}}'
EXPECT="candidate-identity,vulnerability-policy,migration-policy"

check "every expected leg succeeded"                               0 "$ALL_GREEN" "$EXPECT"
check "a failed leg fails the gate"                               1 '{"candidate-identity":{"result":"failure"},"vulnerability-policy":{"result":"success"},"migration-policy":{"result":"success"}}' "$EXPECT"
check "a cancelled leg fails the gate"                            1 '{"candidate-identity":{"result":"success"},"vulnerability-policy":{"result":"cancelled"},"migration-policy":{"result":"success"}}' "$EXPECT"
check "a skipped leg fails the gate"                              1 '{"candidate-identity":{"result":"success"},"vulnerability-policy":{"result":"success"},"migration-policy":{"result":"skipped"}}' "$EXPECT"
check "an expected leg missing from needs fails the gate"         1 '{"candidate-identity":{"result":"success"},"vulnerability-policy":{"result":"success"}}' "$EXPECT"
check "a leg wired into needs but not expected fails the gate"    1 "$ALL_GREEN" "candidate-identity,vulnerability-policy"
check "zero expected legs fails the gate"                         1 "$ALL_GREEN" ""
check "no legs and no needs fails the gate (nothing to aggregate)" 1 '{}' ""
check "an empty needs object fails the gate"                      1 '{}' "$EXPECT"
check "unset NEEDS_JSON fails the gate"                           1 "" "$EXPECT"
check "non-JSON needs fails the gate"                             1 'not json' "$EXPECT"

echo "----"
echo "promotion-gate.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
